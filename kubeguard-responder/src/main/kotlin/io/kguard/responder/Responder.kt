package io.kguard.responder

import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executor

/**
 * Handles KubeGuard alerts from Alertmanager:
 *
 * ```
 * firing -> ask the diagnosis agent -> ActionPolicy -> execute the action -> wait for KubeGuard's "resolved"
 *                                                    \-> escalate: GitHub issue
 * ```
 *
 * - One run per service at a time: alerts that fire together or while a run is open (a slow database fires
 *   downstream-latency and service-latency) share one diagnosis and at most one action. Alertmanager's repeated
 *   notifications are ignored.
 * - The fix counts only when KubeGuard resolves all alerts of the run within [verifyTimeout] after the action
 *   (deterministic verification). Otherwise the incident is escalated.
 * - A "resolved" before any action ends the run without acting.
 * - If the agent fails or gives no valid diagnosis, the alert's [defaultActions] entry (if any) is used instead and
 *   checked by the same policy and autonomy level; the run notes that it acted without a diagnosis.
 * - Every decision is also written to the [audit] log (Kubernetes Events on the Deployment), outside the responder's memory.
 * - Every verified fix opens a follow-up issue ([reportFixes]): a restart clears the symptom, not the cause, and a
 *   rollback leaves the breaking change in the repo. The policy stops acting once a Deployment needed too many actions.
 * - [reportOnlyAlerts] (stuck Pending / Terminating pods) are handled for any workload, not only [services]: the
 *   agent diagnoses them, and the run is always escalated. No allowed action fixes a stuck pod.
 * - An action the policy allows runs under its [autonomy] level: AUTO runs it, APPROVE waits up to [approvalTimeout]
 *   for [approve] or [reject] and checks the policy again before running it, SHADOW only records it and escalates.
 *   [dryRun] puts every action in shadow mode.
 */
enum class DecisionResult { DONE, NOT_FOUND, NOT_WAITING }

class Responder(
    private val agent: DiagnosisAgent,
    private val policy: ActionPolicy,
    private val cluster: Cluster,
    private val issues: IssueTracker,
    private val executor: Executor,
    private val clock: Clock,
    private val namespace: String,
    private val services: Set<String>,
    private val verifyTimeout: Duration,
    private val dryRun: Boolean = false,
    /** After a fix that worked, open an issue anyway: the cause is still in the code, config or repo. */
    private val reportFixes: Boolean = true,
    private val reportOnlyAlerts: Set<String> = emptySet(),
    private val autonomy: Map<Action, Autonomy> = Action.entries.associateWith { Autonomy.AUTO },
    private val approvalTimeout: Duration = Duration.ofMinutes(15),
    private val defaultActions: Map<String, Action> = emptyMap(),
    private val audit: AuditLog = AuditLog { _, _, _ -> },
) {
    private val log = Logger.getLogger(Responder::class.java)
    private val runs = LinkedHashMap<String, IncidentRun>()
    private val runOfAlert = HashMap<String, IncidentRun>()
    private val alerts = HashMap<String, AmAlert>()

    fun onWebhook(payload: AlertmanagerWebhook) {
        val relevant = payload.alerts.filter { it.labels["source"] == "kubeguard" }.filter { alert ->
            (alert.service in services || alert.alertName in reportOnlyAlerts).also {
                if (!it) log.debugf("ignoring alert for service %s (not in scope %s)", alert.service, services)
            }
        }
        relevant.filter { it.status == "resolved" }.forEach(::onResolved)
        relevant.filter { it.status == "firing" }.groupBy { it.service }.forEach { (_, firing) -> onFiring(firing) }
    }

    fun runs(): List<IncidentRun> = synchronized(this) { runs.values.toList() }

    /** Escalates runs whose fix was not confirmed in time, or whose proposal nobody approved in time. Called periodically. */
    fun checkTimeouts() {
        val now = clock.instant()
        val (unverified, unapproved) = synchronized(this) {
            val unverified = runs.values.filter { it.phase == Phase.VERIFYING && it.actionAt!!.plus(verifyTimeout).isBefore(now) }
            val unapproved = runs.values.filter { it.phase == Phase.AWAITING_APPROVAL && it.approvalDeadline!!.isBefore(now) }
            (unverified + unapproved).forEach { it.phase = Phase.ESCALATED }
            unverified to unapproved
        }
        unverified.forEach { escalate(it, "KubeGuard did not report recovery within $verifyTimeout after the action") }
        unapproved.forEach { escalate(it, "nobody approved ${it.decision} within $approvalTimeout") }
    }

    /**
     * A human approves the proposal of a run waiting for approval. The policy is checked again first: the cluster may
     * have changed while it waited (the pod is gone, another action ran). Returns what happened, for the caller.
     */
    fun approve(incidentId: String, by: String): DecisionResult {
        val run = synchronized(this) {
            val run = runs[incidentId] ?: return DecisionResult.NOT_FOUND
            if (run.phase != Phase.AWAITING_APPROVAL) return DecisionResult.NOT_WAITING
            run.decidedBy = by
            run
        }
        note(run, "approved by $by: ${run.decision}")
        val decision = try {
            policy.decide(run.diagnosis!!, run.affected, clock.instant())
        } catch (e: Exception) {
            Decision.Escalate("the policy check failed: ${e.message ?: e.javaClass.simpleName}")
        }
        when (decision) {
            is Decision.Escalate -> escalateIf(run, Phase.AWAITING_APPROVAL, "approved by $by, but the policy now refuses: ${decision.reason}")
            is Decision.Execute -> act(run, decision, from = Phase.AWAITING_APPROVAL)
        }
        return DecisionResult.DONE
    }

    /**
     * A human judges what the responder decided or proposed, for the pilot report: in shadow mode "would this have
     * been right?", otherwise "was it right?". Only for finished runs; a later verdict replaces an earlier one.
     */
    fun judge(incidentId: String, verdict: Verdict, by: String): DecisionResult {
        val run = synchronized(this) {
            val run = runs[incidentId] ?: return DecisionResult.NOT_FOUND
            if (run.finishedAt == null) return DecisionResult.NOT_WAITING
            run.verdict = verdict
            run.verdictBy = by
            run
        }
        note(run, "verdict by $by: ${verdict.name.lowercase()}")
        return DecisionResult.DONE
    }

    /** A human rejects the proposal of a run waiting for approval: nothing is done and the incident is escalated. */
    fun reject(incidentId: String, by: String): DecisionResult {
        val run = synchronized(this) {
            val run = runs[incidentId] ?: return DecisionResult.NOT_FOUND
            if (run.phase != Phase.AWAITING_APPROVAL) return DecisionResult.NOT_WAITING
            run.decidedBy = by
            run
        }
        escalateIf(run, Phase.AWAITING_APPROVAL, "rejected by $by: ${run.decision}")
        return DecisionResult.DONE
    }

    /** New alerts of one service: attached to its open run, or the start of a new run. */
    private fun onFiring(firing: List<AmAlert>) {
        val (run, fresh, attached) = synchronized(this) {
            val fresh = firing.filter { it.incidentId !in runOfAlert }
            if (fresh.isEmpty()) return
            fresh.forEach { alerts[it.incidentId] = it }
            val open = runs.values.lastOrNull { it.service == fresh.first().service && it.phase in OPEN }
            val run = open ?: IncidentRun(fresh.first().incidentId, fresh.first().alertName, fresh.first().service,
                fresh.first().dependency, clock.instant()).also { runs[it.incidentId] = it }
            fresh.forEach {
                runOfAlert[it.incidentId] = run
                run.alerts += it.incidentId
                run.firing += it.incidentId
            }
            Triple(run, fresh, open != null)
        }
        fresh.forEach { note(run, "firing: ${it.alertName} -> ${it.dependency}: ${it.annotations["description"].orEmpty()}") }
        if (attached) {
            note(run, "attached to the open incident; no second diagnosis")
        } else {
            executor.execute { diagnoseAndAct(run, fresh) }
        }
    }

    private fun onResolved(alert: AmAlert) {
        val run = synchronized(this) { runOfAlert[alert.incidentId] } ?: return
        val outcome = synchronized(this) {
            if (!run.firing.remove(alert.incidentId)) return
            if (run.firing.isNotEmpty()) {
                "resolved: ${alert.alertName} -> ${alert.dependency}; still firing: ${run.firing.size}"
            } else when (run.phase) {
                Phase.VERIFYING -> {
                    run.phase = Phase.FIXED
                    run.finishedAt = clock.instant()
                    "FIXED: KubeGuard resolved all alerts ${Duration.between(run.actionAt, run.finishedAt).toSeconds()}s after the action"
                }
                Phase.DIAGNOSING, Phase.AWAITING_APPROVAL, Phase.ACTING -> {
                    run.phase = Phase.RESOLVED_BEFORE_ACTION
                    run.finishedAt = clock.instant()
                    "resolved by itself before any action"
                }
                else -> "resolved: ${alert.alertName} -> ${alert.dependency} (incident already ${run.phase})"
            }
        }
        note(run, outcome)
        if (run.phase == Phase.FIXED) {
            audit(run, AuditReason.KGuardVerified, outcome)
            if (reportFixes) followUp(run, followUpReason(run))
        }
    }

    /** Why a fixed incident still needs a human: what the action did not fix, and how often it was needed. */
    private fun followUpReason(run: IncidentRun): String {
        val deployment = run.deployment ?: "the Deployment"
        val count = run.deployment?.let { policy.actionsWithin(it, clock.instant()) } ?: 1
        val budget = "automatic actions on $deployment within ${policy.actionWindow}: $count of ${policy.maxActions}; " +
            "at ${policy.maxActions} the responder stops acting and leaves it to a human"
        return if (run.decision?.startsWith(Action.ROLLBACK.id) == true) {
            "the incident was fixed by a rollback in the cluster, but the repo still declares the change that broke " +
                "it; the next deploy brings it back unless it is fixed there ($budget)"
        } else {
            "the incident was fixed by ${run.decision}, which clears the symptom, not the cause (for example a leak, " +
                "or a value read only at startup); the cause still needs a fix in the code or config ($budget)"
        }
    }

    /** An issue for a fixed incident that still needs a change in the repo. Does not change the run's phase. */
    private fun followUp(run: IncidentRun, reason: String) {
        val alert = synchronized(this) { alerts[run.incidentId] } ?: return
        try {
            val url = issues.report(run, alert, reason)
            synchronized(this) { run.issueUrl = url }
            note(run, "follow-up issue: $url")
        } catch (e: Exception) {
            note(run, "could not open the follow-up issue: ${e.message}")
        }
    }

    private fun diagnoseAndAct(run: IncidentRun, firing: List<AmAlert>) {
        // Causes first: the outgoing-call alerts point at a dependency, the endpoint latency is the symptom.
        val ordered = firing.sortedBy { if (it.alertName == "KubeGuardServiceLatencyHigh") 1 else 0 }
        val answer = try {
            agent.ask(Prompt.forAlerts(ordered, namespace))
        } catch (e: Exception) {
            return withoutDiagnosis(run, ordered, "the diagnosis agent failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val diagnosis = DiagnosisParser.parse(answer)
        synchronized(this) {
            run.agentAnswer = answer
            run.diagnosis = diagnosis
        }
        if (diagnosis == null) return withoutDiagnosis(run, ordered, "the agent's answer had no valid diagnosis block")
        note(run, "diagnosis: ${diagnosis.summary} (confidence ${diagnosis.confidence}, " +
            "proposes ${diagnosis.action} ${diagnosis.target.orEmpty()}, fix by ${diagnosis.fixBy})")
        decideAndAct(run, ordered, diagnosis)
    }

    /**
     * The agent gave nothing usable: fall back to the configured default action of the first alert that has one, or
     * escalate. A pod restart needs exactly one affected pod (several slow pods point at the dependency, not a pod).
     */
    private fun withoutDiagnosis(run: IncidentRun, ordered: List<AmAlert>, reason: String) {
        val alert = ordered.firstOrNull { it.alertName in defaultActions && it.alertName !in reportOnlyAlerts }
            ?: return escalateIfOpen(run, reason)
        val action = defaultActions.getValue(alert.alertName)
        val target = if (action == Action.RESTART_POD) {
            AffectedInstances.of(alert).singleOrNull()
                ?: return escalateIfOpen(run, "$reason; the default restart-pod needs exactly one affected pod, " +
                    "KubeGuard reported ${AffectedInstances.of(alert).size}")
        } else {
            alert.service
        }
        val fallback = Diagnosis(
            summary = "no AI diagnosis ($reason); default action for ${alert.alertName}",
            rootCause = "unknown: acted on the KubeGuard rule alone",
            confidence = "high", // the rule's evidence is deterministic; the policy's other checks still apply
            action = action.id,
            target = target,
            fixBy = "kagent",
        )
        synchronized(this) { run.diagnosis = fallback }
        note(run, "WITHOUT DIAGNOSIS: $reason; using the default ${action.id} $target for ${alert.alertName}")
        decideAndAct(run, ordered, fallback)
    }

    private fun decideAndAct(run: IncidentRun, ordered: List<AmAlert>, diagnosis: Diagnosis) {
        if (ordered.all { it.alertName in reportOnlyAlerts }) {
            return escalateIfOpen(run, "report only: the responder has no allowed action for " +
                "${ordered.map { it.alertName }.distinct().joinToString(", ")} (fix by: ${diagnosis.fixBy})")
        }

        val affected = ordered.flatMap { AffectedInstances.of(it) }.toSet()
        synchronized(this) { run.affected = affected }
        // The policy reads the cluster (owners, revisions); a failure there must escalate, not vanish in this thread.
        val decision = try {
            policy.decide(diagnosis, affected, clock.instant())
        } catch (e: Exception) {
            Decision.Escalate("the policy check failed: ${e.message ?: e.javaClass.simpleName}")
        }
        when (decision) {
            is Decision.Escalate -> escalateIfOpen(run, decision.reason)
            is Decision.Execute -> when (if (dryRun) Autonomy.SHADOW else autonomy[decision.action] ?: Autonomy.SHADOW) {
                Autonomy.AUTO -> act(run, decision, from = Phase.DIAGNOSING)
                Autonomy.APPROVE -> requestApproval(run, decision)
                Autonomy.SHADOW -> shadow(run, decision)
            }
        }
    }

    /** Waits for [approve] or [reject]; the issue names the proposal, so a human can decide from there too. */
    private fun requestApproval(run: IncidentRun, decision: Decision.Execute) {
        val deadline = clock.instant().plus(approvalTimeout)
        synchronized(this) {
            if (run.phase != Phase.DIAGNOSING) return note(run, "not asking for approval: incident already ${run.phase}")
            run.phase = Phase.AWAITING_APPROVAL
            run.autonomy = Autonomy.APPROVE
            run.decision = "${decision.action.id} ${decision.target}"
            run.approvalDeadline = deadline
        }
        note(run, "AWAITING APPROVAL until $deadline: ${decision.action.id} ${decision.target} (allowed by the policy)")
        audit(run, AuditReason.KGuardAwaitingApproval, "proposes ${decision.action.id} ${decision.target}, waits for approval until $deadline")
        followUp(run, "approval needed: KubeGuard proposes ${decision.action.id} ${decision.target}, which the policy " +
            "allows. Approve or reject it in the console before $deadline; without approval nothing is done")
    }

    /** Records what the responder would have done and hands the incident to a human. Nothing changes in the cluster. */
    private fun shadow(run: IncidentRun, decision: Decision.Execute) {
        synchronized(this) {
            if (run.phase != Phase.DIAGNOSING) return note(run, "not recording the shadow action: incident already ${run.phase}")
            run.phase = Phase.SHADOWED
            run.autonomy = Autonomy.SHADOW
            run.decision = "${decision.action.id} ${decision.target}"
        }
        note(run, "SHADOW: would ${decision.action.id} ${decision.target} (allowed by the policy); nothing done")
        audit(run, AuditReason.KGuardShadow, "would ${decision.action.id} ${decision.target} (allowed by the policy); nothing done")
        escalate(run, "shadow mode: KubeGuard would ${decision.action.id} ${decision.target}, which the policy allows, " +
            "but this action is not enabled yet. Fix it by hand and note whether the proposal was right")
    }

    private fun act(run: IncidentRun, decision: Decision.Execute, from: Phase) {
        synchronized(this) {
            if (run.phase != from) return note(run, "not acting: incident already ${run.phase}")
            run.phase = Phase.ACTING
            run.autonomy = run.autonomy ?: Autonomy.AUTO
            run.decision = "${decision.action.id} ${decision.target}"
        }
        try {
            when (decision.action) {
                Action.RESTART_POD -> cluster.deletePod(decision.target)
                Action.ROLLOUT_RESTART -> cluster.rolloutRestart(decision.target)
                Action.ROLLBACK -> cluster.rollback(decision.target)
                Action.NONE -> error("not executable")
            }
        } catch (e: Exception) {
            synchronized(this) { run.phase = Phase.ESCALATED }
            return escalate(run, "${decision.action.id} ${decision.target} failed: ${e.message}")
        }
        val now = clock.instant()
        policy.recordAction(decision.deployment, now)
        synchronized(this) {
            run.deployment = decision.deployment
            run.actionAt = now
            if (run.phase == Phase.ACTING) run.phase = Phase.VERIFYING
        }
        note(run, "action executed: ${decision.action.id} ${decision.target}; waiting up to $verifyTimeout for KubeGuard to resolve")
        audit(run, AuditReason.KGuardAction, "${decision.action.id} ${decision.target} (${run.autonomy?.name?.lowercase()}" +
            (run.decidedBy?.let { ", approved by $it" } ?: "") + "); diagnosis: ${run.diagnosis?.summary.orEmpty()}")
    }

    private fun escalateIfOpen(run: IncidentRun, reason: String) = escalateIf(run, Phase.DIAGNOSING, reason)

    private fun escalateIf(run: IncidentRun, expected: Phase, reason: String) {
        synchronized(this) {
            if (run.phase != expected) return note(run, "not escalating ($reason): incident already ${run.phase}")
            run.phase = Phase.ESCALATED
        }
        escalate(run, reason)
    }

    private fun escalate(run: IncidentRun, reason: String) {
        note(run, "ESCALATED: $reason")
        audit(run, AuditReason.KGuardEscalated, reason)
        val alert = synchronized(this) { alerts[run.incidentId] } ?: return
        try {
            val url = issues.report(run, alert, reason)
            synchronized(this) { run.issueUrl = url }
            note(run, "issue: $url")
        } catch (e: Exception) {
            note(run, "could not open the GitHub issue: ${e.message}")
        } finally {
            synchronized(this) { run.finishedAt = run.finishedAt ?: clock.instant() }
        }
    }

    /** The audit record must never stop the incident handling: a failed write is only noted. */
    private fun audit(run: IncidentRun, reason: AuditReason, message: String) {
        try {
            audit.record(run, reason, message)
        } catch (e: Exception) {
            note(run, "could not write the audit event ${reason.name}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun note(run: IncidentRun, message: String) {
        synchronized(this) { run.timeline += "${clock.instant()} $message" }
        log.infof("incident=%s service=%s dependency=%s phase=%s %s",
            run.incidentId, run.service, run.dependency, run.phase, message)
    }

    private companion object {
        val OPEN = setOf(Phase.DIAGNOSING, Phase.AWAITING_APPROVAL, Phase.ACTING, Phase.VERIFYING)
    }
}
