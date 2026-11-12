package io.kguard.responder

import java.time.Instant

/** Alertmanager webhook payload (version 4), reduced to what the responder reads. */
data class AlertmanagerWebhook(
    val status: String = "",
    val alerts: List<AmAlert> = emptyList(),
)

data class AmAlert(
    val status: String = "",
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val startsAt: String? = null,
    val endsAt: String? = null,
    val fingerprint: String = "",
) {
    /** KubeGuard's id of this incident occurrence; Alertmanager's fingerprint as fallback. */
    val incidentId: String get() = annotations["incident_id"]?.takeIf { it.isNotBlank() } ?: fingerprint
    val service: String get() = labels["service"].orEmpty()
    val dependency: String get() = labels["dependency"].orEmpty()
    val alertName: String get() = labels["alertname"].orEmpty()
}

/** The actions the responder may execute. Everything else is reported, not done. */
enum class Action(val id: String) {
    RESTART_POD("restart-pod"),
    ROLLOUT_RESTART("rollout-restart"),
    ROLLBACK("rollback"),
    NONE("none");

    companion object {
        fun of(id: String?): Action? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/** The structured part of the agent's answer (the fenced `json` block). */
data class Diagnosis(
    val summary: String = "",
    val rootCause: String = "",
    val confidence: String = "low",
    val action: String = "none",
    val target: String? = null,
    /** Who should fix it: kagent (an allowed action), code, config or human. */
    val fixBy: String = "human",
    val evidence: List<String> = emptyList(),
)

/**
 * The action per KubeGuard alert the responder falls back to when the diagnosis agent fails or gives no valid answer
 * (`responder.default-actions`, e.g. `KubeGuardMemoryExhaustionPredicted=rollout-restart`). The default goes through
 * the same policy and autonomy level as the agent's proposal: the AI explains, it is not needed to act safely.
 */
object DefaultActions {
    fun parse(spec: String): Map<String, Action> = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .associate { entry ->
            val (alert, action) = entry.split("=", limit = 2).map { it.trim() }.let { it[0] to it.getOrElse(1) { "" } }
            require(alert.isNotEmpty()) { "responder.default-actions: missing alert name in '$entry'" }
            alert to (Action.of(action)?.takeIf { it != Action.NONE }
                ?: throw IllegalArgumentException("responder.default-actions: '$alert' needs restart-pod, rollout-restart or rollback, not '$action'"))
        }
}

/**
 * How far the responder may go with an action the policy allows, set per action (`responder.autonomy`):
 * - [SHADOW]: only record what it would do and hand the incident to a human (an issue that names the proposal)
 * - [APPROVE]: wait for a human to approve or reject it; the policy is checked again before it runs
 * - [AUTO]: run it at once
 * A customer starts with everything in shadow mode and promotes one action at a time on its measured record.
 */
enum class Autonomy {
    SHADOW, APPROVE, AUTO;

    companion object {
        /** `restart-pod=auto,rollback=approve`; actions that are not listed stay in shadow mode. */
        fun parse(spec: String): Map<Action, Autonomy> = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .associate { entry ->
                val (action, mode) = entry.split("=", limit = 2).map { it.trim() }.let { it[0] to it.getOrElse(1) { "" } }
                (Action.of(action)?.takeIf { it != Action.NONE }
                    ?: throw IllegalArgumentException("responder.autonomy: unknown action '$action'")) to
                    (entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
                        ?: throw IllegalArgumentException("responder.autonomy: '$action' needs shadow, approve or auto, not '$mode'"))
            }
    }
}

enum class Phase { DIAGNOSING, AWAITING_APPROVAL, ACTING, VERIFYING, FIXED, RESOLVED_BEFORE_ACTION, SHADOWED, ESCALATED }

/**
 * One incident as the responder handles it, exposed by `GET /api/incidents`. It starts with the alerts of one service
 * that fire together (e.g. a slow database fires downstream-latency and service-latency) and collects further alerts
 * of that service while it is open. [incidentId] is the first alert's KubeGuard incident id.
 */
data class IncidentRun(
    val incidentId: String,
    val alertName: String,
    val service: String,
    val dependency: String,
    val receivedAt: Instant,
    var phase: Phase = Phase.DIAGNOSING,
    var diagnosis: Diagnosis? = null,
    var agentAnswer: String? = null,
    var decision: String? = null,
    /** The autonomy level the decided action ran under, and for [Phase.AWAITING_APPROVAL] until when it waits. */
    var autonomy: Autonomy? = null,
    var approvalDeadline: Instant? = null,
    /** Who approved or rejected the proposal. */
    var decidedBy: String? = null,
    /** The pods KubeGuard reported as affected, kept to check the policy again on approval. */
    var affected: Set<String> = emptySet(),
    /** The Deployment the action targeted (for a pod restart, the pod's owner). */
    var deployment: String? = null,
    var actionAt: Instant? = null,
    var finishedAt: Instant? = null,
    var issueUrl: String? = null,
    /** A human's judgement of the decision or proposal (pilot report), and who gave it. */
    var verdict: Verdict? = null,
    var verdictBy: String? = null,
    val timeline: MutableList<String> = mutableListOf(),
    /** KubeGuard incident ids of all alerts in this run, and those still firing. */
    val alerts: MutableList<String> = mutableListOf(),
    val firing: MutableSet<String> = mutableSetOf(),
)
