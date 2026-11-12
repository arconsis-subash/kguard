package io.kguard.alerting

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

/** One element of the Alertmanager `POST /api/v2/alerts` body. Alertmanager identifies an alert by its labels. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AlertmanagerAlert(
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val startsAt: Instant,
    /** In the future while firing (refreshed on every resend), `now` when resolved. */
    val endsAt: Instant,
    val generatorURL: String? = null,
)

/** Delivers alerts to Alertmanager. Throws on failure; the publisher retries on its next flush. */
fun interface AlertSink {
    fun send(alerts: List<AlertmanagerAlert>)
}
