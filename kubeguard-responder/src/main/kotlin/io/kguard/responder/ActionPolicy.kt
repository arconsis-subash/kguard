package io.kguard.responder

import java.time.Duration
import java.time.Instant

/** What the responder may look at and change in the target namespace. */
interface Cluster {
    /** Name of the Deployment that owns the pod (via its ReplicaSet), or null if the pod is missing or not owned by one. */
    fun deploymentOfPod(pod: String): String?

    fun deploymentExists(name: String): Boolean

    fun deletePod(pod: String)

    fun rolloutRestart(deployment: String)

    /** Revision a rollback would return to (the newest older ReplicaSet), or null if there is none. */
    fun previousRevision(deployment: String): Long?

    /**
     * The newest revision does not come up while an older one still serves: its ReplicaSet has fewer ready pods than
     * wanted and an older ReplicaSet still has ready pods. That is when a rollback can help.
     */
    fun rolloutStuck(deployment: String): Boolean

    /** `kubectl rollout undo deployment/<deployment>`: back to the previous revision. */
    fun rollback(deployment: String)
}

sealed interface Decision {
    data class Execute(val action: Action, val target: String, val deployment: String) : Decision
    data class Escalate(val reason: String) : Decision
}

/**
 * Deterministic gate between the agent's proposal and the cluster. The agent only proposes; an action runs only
 * if every rule here holds. Anything else is escalated as a GitHub issue.
 *
 * - action is `restart-pod`, `rollout-restart` or `rollback` (`none` means the agent says it cannot be fixed here)
 * - `rollback`: the Deployment has an older revision to go back to, and its rollout is stuck (the newest revision
 *   does not come up while an older one still serves); pods that crash for other reasons are not rolled back
 * - confidence is one of [minConfidence] or higher
 * - the target belongs to one of [allowedDeployments] (never a StatefulSet such as PostgreSQL)
 * - `restart-pod`: the pod is one of the instances KubeGuard reported, if it reported any
 * - at most one action per Deployment within [cooldown]
 * - at most [maxActions] actions per Deployment within [actionWindow]: a fix that keeps coming back (a leak restarted
 *   every hour) is not fixed, so beyond that the incident goes to a human instead of being restarted again
 */
class ActionPolicy(
    private val cluster: Cluster,
    private val allowedDeployments: Set<String>,
    private val minConfidence: String,
    private val cooldown: Duration,
    val maxActions: Int = 3,
    val actionWindow: Duration = Duration.ofHours(24),
) {
    // In memory: a restart of the responder starts the count again.
    private val actions = HashMap<String, MutableList<Instant>>()
    private val confidenceOrder = listOf("low", "medium", "high")

    @Synchronized
    fun decide(diagnosis: Diagnosis, affectedInstances: Set<String>, now: Instant): Decision {
        val action = Action.of(diagnosis.action)
            ?: return Decision.Escalate("the agent proposed the unknown action '${diagnosis.action}'")
        if (action == Action.NONE) {
            return Decision.Escalate("the agent found no allowed action that fixes this (fix by: ${diagnosis.fixBy})")
        }
        if (confidenceOrder.indexOf(diagnosis.confidence.lowercase()) < confidenceOrder.indexOf(minConfidence)) {
            return Decision.Escalate("the agent's confidence '${diagnosis.confidence}' is below '$minConfidence'")
        }
        val target = diagnosis.target?.trim().orEmpty()
        if (target.isEmpty()) return Decision.Escalate("the agent proposed ${action.id} without a target")
        // A Deployment target is checked before any cluster read: the responder's Role may only read the allowed
        // Deployments (resourceNames), so a lookup of any other one fails with 403 instead of a clear reason.
        if (action != Action.RESTART_POD && target !in allowedDeployments) {
            return Decision.Escalate("Deployment $target is not in the allow-list $allowedDeployments")
        }

        val deployment = when (action) {
            Action.RESTART_POD -> {
                if (affectedInstances.isNotEmpty() && target !in affectedInstances) {
                    return Decision.Escalate("pod $target is not one of the affected instances $affectedInstances")
                }
                cluster.deploymentOfPod(target)
                    ?: return Decision.Escalate("pod $target does not exist or is not owned by a Deployment")
            }
            Action.ROLLOUT_RESTART -> target.takeIf { cluster.deploymentExists(it) }
                ?: return Decision.Escalate("Deployment $target does not exist")
            Action.ROLLBACK -> {
                if (!cluster.deploymentExists(target)) return Decision.Escalate("Deployment $target does not exist")
                cluster.previousRevision(target)
                    ?: return Decision.Escalate("Deployment $target has no previous revision to roll back to")
                if (!cluster.rolloutStuck(target)) {
                    return Decision.Escalate("the rollout of $target is not stuck (no newer revision failing while " +
                        "an older one serves), so a rollback would not undo the cause")
                }
                target
            }
            Action.NONE -> error("handled above")
        }
        if (deployment !in allowedDeployments) {
            return Decision.Escalate("Deployment $deployment is not in the allow-list $allowedDeployments")
        }
        actions[deployment]?.lastOrNull()?.let {
            if (Duration.between(it, now) < cooldown) {
                return Decision.Escalate("Deployment $deployment was already acted on at $it (cooldown $cooldown)")
            }
        }
        val recent = actionsWithin(deployment, now)
        if (recent >= maxActions) {
            return Decision.Escalate("Deployment $deployment was already acted on $recent times within $actionWindow " +
                "(limit $maxActions): the fix does not last, so the cause needs a fix in the code or config")
        }
        return Decision.Execute(action, target, deployment)
    }

    @Synchronized
    fun recordAction(deployment: String, at: Instant) {
        actions.getOrPut(deployment) { mutableListOf() } += at
    }

    /** Actions on [deployment] within [actionWindow] before [now], this one included once recorded. */
    @Synchronized
    fun actionsWithin(deployment: String, now: Instant): Int =
        actions[deployment].orEmpty().count { Duration.between(it, now) < actionWindow }
}
