package io.kguard.responder

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Reads the active alerts from Alertmanager (`GET /api/v2/alerts?active=true`). The responder keeps its runs in
 * memory, and Alertmanager does not resend an alert that is already active, so after a restart the responder would
 * never learn about it. A periodic sync closes that gap; already known incidents are ignored by the responder.
 */
class AlertmanagerSync(private val baseUrl: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun activeAlerts(): List<AmAlert> {
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/v2/alerts?active=true&silenced=false&inhibited=false"))
            .timeout(Duration.ofSeconds(5)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Alertmanager answered HTTP ${response.statusCode()}" }
        // API v2 has status as an object; the webhook format the responder handles has "firing".
        return mapper.readValue<List<Map<String, Any?>>>(response.body()).map {
            @Suppress("UNCHECKED_CAST")
            AmAlert(
                status = "firing",
                labels = (it["labels"] as? Map<String, String>).orEmpty(),
                annotations = (it["annotations"] as? Map<String, String>).orEmpty(),
                startsAt = it["startsAt"] as? String,
                endsAt = it["endsAt"] as? String,
                fingerprint = it["fingerprint"] as? String ?: "",
            )
        }
    }
}
