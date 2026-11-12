package io.kguard.responder

/** The diagnosis agent (kagent over A2A). Returns the agent's full text answer. */
fun interface DiagnosisAgent {
    fun ask(prompt: String): String
}

/** Where incidents the responder cannot fix end up. Returns the issue URL. */
fun interface IssueTracker {
    fun report(run: IncidentRun, alert: AmAlert, reason: String): String
}

/**
 * A record of every decision outside the responder's memory: what it did or would have done, and how it ended.
 * In the cluster: Kubernetes Events on the Deployment (`kubectl describe deployment`, `kubectl get events`).
 */
fun interface AuditLog {
    fun record(run: IncidentRun, reason: AuditReason, message: String)
}

enum class AuditReason(val warning: Boolean = false) {
    KGuardAction, KGuardVerified, KGuardShadow, KGuardAwaitingApproval, KGuardEscalated(warning = true)
}
