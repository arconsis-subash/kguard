package io.kguard.alerting

import io.kguard.engine.IncidentKey
import io.kguard.engine.IncidentState
import io.kguard.engine.Transition
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant

/**
 * Keeps the alerts KubeGuard wants Alertmanager to know about and syncs them on every [flush]:
 *
 * - FIRING transition: the alert is sent once right away, then re-sent every [resendInterval] with `endsAt` pushed
 *   [alertTtl] into the future, as Alertmanager expects from a client. If KubeGuard dies, the alert expires on
 *   its own after [alertTtl].
 * - RECOVERED transition: the alert is sent once with `endsAt = now` (resolved) and then forgotten.
 * - A failed delivery keeps the alert pending, so the next flush retries it. Re-sending the same labels never
 *   creates a duplicate in Alertmanager; it only updates the existing alert.
 */
class AlertPublisher(
    private val sink: AlertSink,
    private val resendInterval: Duration,
    private val alertTtl: Duration,
    private val staticLabels: Map<String, String> = emptyMap(),
    private val generatorUrl: String? = null,
) {
    private class Pending(
        val labels: Map<String, String>,
        var annotations: Map<String, String>,
        val startsAt: Instant,
        var resolvedAt: Instant? = null,
        var lastSentAt: Instant? = null,
        var dirty: Boolean = true,
    )

    private val log = Logger.getLogger(AlertPublisher::class.java)
    private val alerts = LinkedHashMap<IncidentKey, Pending>()

    var lastDeliveryOk: Boolean? = null
        private set
    var lastDeliveryAt: Instant? = null
        private set

    @Synchronized
    fun apply(transition: Transition) {
        when (transition.to) {
            IncidentState.FIRING -> alerts[transition.key] = Pending(
                labels = labels(transition),
                annotations = annotations(transition),
                startsAt = transition.firingSince ?: transition.at,
            )

            IncidentState.RECOVERED -> alerts[transition.key]?.let {
                it.resolvedAt = transition.at
                it.annotations = annotations(transition)
                it.dirty = true
            }

            else -> Unit
        }
    }

    /** Sends every alert that is new, changed, failed earlier or due for a refresh. Returns the number sent. */
    @Synchronized
    fun flush(now: Instant): Int {
        val due = alerts.values.filter { p ->
            p.dirty || p.lastSentAt?.let { Duration.between(it, now) >= resendInterval } ?: true
        }
        if (due.isEmpty()) return 0
        val body = due.map { p ->
            AlertmanagerAlert(p.labels, p.annotations, p.startsAt, p.resolvedAt ?: now.plus(alertTtl), generatorUrl)
        }
        try {
            sink.send(body)
        } catch (e: Exception) {
            lastDeliveryOk = false
            log.warnf("alertmanager delivery failed alerts=%d error=%s, retrying on next evaluation", body.size, e.toString())
            return 0
        }
        lastDeliveryOk = true
        lastDeliveryAt = now
        due.forEach {
            it.lastSentAt = now
            it.dirty = false
        }
        alerts.values.removeIf { it.resolvedAt != null && !it.dirty }
        return body.size
    }

    @Synchronized
    fun activeAlerts(): Int = alerts.values.count { it.resolvedAt == null }

    private fun labels(t: Transition): Map<String, String> = staticLabels + mapOf(
        "alertname" to t.alertName,
        "severity" to t.severity,
        "rule" to t.key.rule,
        "service" to t.key.service,
        "dependency" to t.key.dependency,
        "source" to "kubeguard",
    )

    private fun annotations(t: Transition): Map<String, String> {
        val fired = t.firingEvaluation ?: t.evaluation
        val base = mapOf(
            "summary" to summary(t),
            "description" to "${subject(t)}: ${fired.explanation}",
            "incident_id" to (t.incidentId ?: ""),
            "observed_value" to (fired.observed?.toString() ?: ""),
            "threshold" to fired.threshold.toString(),
            "sample_count" to fired.sampleCount.toString(),
        ) + (fired.byInstance?.let { mapOf("by_instance" to it) } ?: emptyMap())
        return if (t.to == IncidentState.RECOVERED) base + ("resolution" to "recovered: ${t.evaluation.explanation}")
        else base
    }

    private fun summary(t: Transition) = when (t.key.rule) {
        "downstream-latency" -> "High latency from ${t.key.service} to ${t.key.dependency}"
        "dependency-connectivity" -> "${t.key.service} cannot reach ${t.key.dependency}"
        "pod-crashloop" -> "Pods of ${t.key.service} are crash-looping"
        "service-latency" -> "Slow responses from ${t.key.service} on ${t.key.dependency}"
        else -> "${t.alertName} for ${subject(t)}"
    }

    /** "service -> dependency" for outgoing calls, "service endpoint" for service-latency. */
    private fun subject(t: Transition) =
        if (t.key.rule == "service-latency") "${t.key.service} ${t.key.dependency}"
        else "${t.key.service} -> ${t.key.dependency}"
}
