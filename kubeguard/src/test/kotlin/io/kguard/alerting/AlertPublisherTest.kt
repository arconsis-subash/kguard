package io.kguard.alerting

import io.kguard.engine.Evaluation
import io.kguard.engine.IncidentKey
import io.kguard.engine.IncidentState
import io.kguard.engine.Transition
import io.kguard.engine.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AlertPublisherTest {
    private val sent = mutableListOf<List<AlertmanagerAlert>>()
    private var failing = false
    private val publisher = AlertPublisher(
        sink = { if (failing) error("connection refused") else sent += it },
        resendInterval = Duration.ofSeconds(30),
        alertTtl = Duration.ofMinutes(3),
        staticLabels = mapOf("namespace" to "kubeguard-demo"),
    )
    private val t0 = Instant.parse("2026-10-01T12:00:00Z")
    private val key = IncidentKey("dependency-connectivity", "mock-backend", "inventory")
    private val met = Evaluation(Verdict.MET, 20, 1.0, 0.5, "20 of 20 calls failed")
    private val clear = Evaluation(Verdict.NOT_MET, 20, 0.0, 0.5, "0 of 20 calls failed")

    private fun transition(to: IncidentState, at: Instant, evaluation: Evaluation) = Transition(
        key, "KubeGuardDependencyConnectivityFailure", "critical", IncidentState.DEGRADED, to, at, evaluation,
        incidentId = "id-1", firingSince = t0, firingEvaluation = met,
    )

    @Test
    fun `firing is sent once, refreshed after the resend interval, then resolved once`() {
        publisher.apply(transition(IncidentState.FIRING, t0, met))
        assertEquals(1, publisher.flush(t0))
        assertEquals(0, publisher.flush(t0.plusSeconds(5)))
        assertEquals(0, publisher.flush(t0.plusSeconds(25)))
        assertEquals(1, publisher.flush(t0.plusSeconds(30)))

        val firing = sent.first().single()
        assertEquals("KubeGuardDependencyConnectivityFailure", firing.labels["alertname"])
        assertEquals("inventory", firing.labels["dependency"])
        assertEquals("kubeguard-demo", firing.labels["namespace"])
        assertEquals(t0, firing.startsAt)
        assertEquals(t0.plusSeconds(180), firing.endsAt)
        assertEquals("id-1", firing.annotations["incident_id"])

        val resolvedAt = t0.plusSeconds(90)
        publisher.apply(transition(IncidentState.RECOVERED, resolvedAt, clear))
        assertEquals(1, publisher.flush(resolvedAt))
        val resolved = sent.last().single()
        assertEquals(firing.labels, resolved.labels)
        assertEquals(resolvedAt, resolved.endsAt)
        assertEquals(0, publisher.activeAlerts())
        assertEquals(0, publisher.flush(resolvedAt.plusSeconds(60)))
        assertEquals(3, sent.size)
    }

    @Test
    fun `failed delivery is retried on the next flush`() {
        failing = true
        publisher.apply(transition(IncidentState.FIRING, t0, met))
        assertEquals(0, publisher.flush(t0))
        assertEquals(false, publisher.lastDeliveryOk)
        failing = false
        assertEquals(1, publisher.flush(t0.plusSeconds(5)))
        assertEquals(true, publisher.lastDeliveryOk)
    }

    @Test
    fun `non-alerting transitions send nothing`() {
        publisher.apply(transition(IncidentState.DEGRADED, t0, met))
        publisher.apply(transition(IncidentState.NORMAL, t0, clear))
        assertEquals(0, publisher.flush(t0))
    }
}
