package io.kguard.engine

import java.time.Duration
import java.time.Instant

/**
 * One outgoing call (an OpenTelemetry CLIENT span) from a service to a dependency, or with [inbound] one request a
 * service answered (a SERVER span), reduced to the fields the rules need. For an inbound request [dependency] is the
 * endpoint, e.g. `GET /api/quote`. [receivedAt] is KubeGuard's own clock: windows never depend on the sender's clock.
 */
data class SpanSample(
    val receivedAt: Instant,
    val service: String,
    val dependency: String,
    val duration: Duration,
    val error: Boolean,
    /** Response status code, or null if no response was received. */
    val httpStatus: Int? = null,
    /** `error.type` / exception type of a failed call. */
    val errorType: String? = null,
    /**
     * The call failed because the dependency could not be reached (refused, connect/read timeout, unknown host, broken
     * connection). Decided by the ingest classifier; the default fits HTTP calls: failed without any response.
     */
    val connectivityFailure: Boolean = error && httpStatus == null,
    /** The instance (pod) that made the call: resource attribute `host.name`, else `service.instance.id`. */
    val instance: String? = null,
    /** A request the service answered (SERVER span), not a call it made. */
    val inbound: Boolean = false,
)

/**
 * Identity of a potential incident: one rule applied to the calls from one service to one dependency, or for an
 * inbound rule to the requests one endpoint of the service answered.
 */
data class IncidentKey(val rule: String, val service: String, val dependency: String) {
    override fun toString() = "$rule[$service -> $dependency]"
}

/**
 * Lifecycle of an incident.
 *
 * ```
 * NORMAL / RECOVERED --condition met---------------------------> DEGRADED
 * DEGRADED           --condition no longer met-----------------> NORMAL
 * DEGRADED           --condition met for firingDuration--------> FIRING     (alert sent to Alertmanager)
 * FIRING             --condition clear for recoveryDuration----> RECOVERED  (resolved sent to Alertmanager)
 * ```
 *
 * RECOVERED behaves like NORMAL; it only shows that the last incident for this key was resolved.
 */
enum class IncidentState { NORMAL, DEGRADED, FIRING, RECOVERED }

/**
 * Settings every rule shares.
 *
 * @property window only samples received within the window are evaluated
 * @property minSamples below this many samples the rule reports INSUFFICIENT_DATA
 * @property firingDuration how long the condition must hold continuously before the incident fires
 * @property recoveryDuration how long the condition must be clear continuously before the incident resolves
 * @property severity Alertmanager `severity` label
 */
data class RuleSettings(
    val window: Duration,
    val minSamples: Int,
    val firingDuration: Duration,
    val recoveryDuration: Duration,
    val severity: String,
)

enum class Verdict {
    /** The incident condition holds. */
    MET,
    /** Enough samples, and the condition does not hold. */
    NOT_MET,
    /** Fewer samples than minSamples: no evidence either way. */
    INSUFFICIENT_DATA,
}

/** Result of evaluating one rule for one key. [explanation] goes into logs and the alert description. */
data class Evaluation(
    val verdict: Verdict,
    val sampleCount: Int,
    val observed: Double?,
    val threshold: Double,
    val explanation: String,
    /** The same measurement per instance (pod), e.g. "pod-a: 20/20 failed; pod-b: 0/19 failed". */
    val byInstance: String? = null,
) {
    companion object {
        fun insufficient(sampleCount: Int, settings: RuleSettings, threshold: Double) = Evaluation(
            Verdict.INSUFFICIENT_DATA, sampleCount, null, threshold,
            "only $sampleCount sample(s) in the last ${settings.window.toSeconds()}s, " +
                "need at least ${settings.minSamples}",
        )
    }
}

/**
 * A state change of one incident. [incidentId], [firingSince] and [firingEvaluation] are set for FIRING and
 * RECOVERED transitions only.
 */
data class Transition(
    val key: IncidentKey,
    val alertName: String,
    val severity: String,
    val from: IncidentState,
    val to: IncidentState,
    val at: Instant,
    val evaluation: Evaluation,
    val incidentId: String? = null,
    val firingSince: Instant? = null,
    val firingEvaluation: Evaluation? = null,
)

/** Read-only snapshot of one incident key, as exposed by `GET /api/incidents`. */
data class IncidentView(
    val rule: String,
    val service: String,
    val dependency: String,
    val state: IncidentState,
    val stateSince: Instant,
    val conditionSince: Instant?,
    val firingSince: Instant?,
    val clearSince: Instant?,
    val incidentId: String?,
    val lastEvaluation: Evaluation?,
)
