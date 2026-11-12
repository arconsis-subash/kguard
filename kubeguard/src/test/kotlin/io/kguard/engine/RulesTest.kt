package io.kguard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RulesTest {
    private val t = Instant.parse("2026-10-01T12:00:00Z")
    private val latency = DownstreamLatencyRule(settings, Duration.ofMillis(500))
    private val connectivity = DependencyConnectivityRule(settings, 0.5)
    private val serviceLatency = ServiceLatencyRule(settings, Duration.ofMillis(250))

    @Test
    fun `latency below threshold is not met`() {
        val result = latency.evaluate(List(20) { ok(t, 50) })
        assertEquals(Verdict.NOT_MET, result.verdict)
        assertEquals(50.0, result.observed)
    }

    @Test
    fun `latency fires on p95 above threshold, not on a single outlier`() {
        val oneOutlier = List(19) { ok(t, 50) } + ok(t, 3000)
        assertEquals(Verdict.NOT_MET, latency.evaluate(oneOutlier).verdict)

        val slow = List(18) { ok(t, 50) } + List(2) { ok(t, 1600) }
        val result = latency.evaluate(slow)
        assertEquals(Verdict.MET, result.verdict)
        assertEquals(1600.0, result.observed)
        assertTrue(result.explanation.contains("1600 ms (threshold 500 ms"), result.explanation)
    }

    @Test
    fun `latency ignores failed calls and needs min samples`() {
        val samples = List(5) { ok(t, 2000) } + List(30) { connectFailure(t, "pricing") }
        assertEquals(Verdict.INSUFFICIENT_DATA, latency.evaluate(samples).verdict)
    }

    @Test
    fun `connectivity counts only calls without a response`() {
        val http503 = List(20) { ok(t, 20).copy(error = true, httpStatus = 503) }
        assertEquals(Verdict.NOT_MET, connectivity.evaluate(http503).verdict)

        val half = List(10) { ok(t, 20) } + List(10) { connectFailure(t) }
        val result = connectivity.evaluate(half)
        assertEquals(Verdict.MET, result.verdict)
        assertTrue(result.explanation.contains("10 of 20 calls"), result.explanation)
        assertTrue(result.explanation.contains("HttpConnectTimeoutException"), result.explanation)

        assertEquals(Verdict.NOT_MET, connectivity.evaluate(List(11) { ok(t, 20) } + List(9) { connectFailure(t) }).verdict)
        assertEquals(Verdict.INSUFFICIENT_DATA, connectivity.evaluate(List(9) { connectFailure(t) }).verdict)
    }

    @Test
    fun `breakdown per instance shows which pod is affected`() {
        val samples = List(10) { ok(t, 20).copy(instance = "pod-b") } +
            List(10) { connectFailure(t).copy(instance = "pod-a") }
        val result = connectivity.evaluate(samples)
        assertEquals(Verdict.MET, result.verdict)
        assertEquals("pod-a: 10/10 calls failed; pod-b: 0/10 calls failed", result.byInstance)
    }

    @Test
    fun `service latency fires on slow successful requests and ignores failed ones`() {
        assertTrue(serviceLatency.inbound)
        assertEquals(Verdict.NOT_MET, serviceLatency.evaluate(List(20) { request(t, 8) }).verdict)

        val slow = List(15) { request(t, 8) } + List(5) { request(t, 450) }
        val result = serviceLatency.evaluate(slow)
        assertEquals(Verdict.MET, result.verdict)
        assertEquals(450.0, result.observed)
        assertTrue(result.explanation.contains("20 successful requests"), result.explanation)

        val failedSlow = List(5) { request(t, 8) } + List(20) { request(t, 3000).copy(error = true, httpStatus = 504) }
        assertEquals(Verdict.INSUFFICIENT_DATA, serviceLatency.evaluate(failedSlow).verdict)
    }
}
