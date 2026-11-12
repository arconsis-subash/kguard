package io.kguard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Drives the engine with a simulated clock: 4 calls/s, evaluation every 5s, like the demo.
 * Settings: window 20s, firing duration 30s, recovery duration 30s, p95 threshold 500 ms.
 */
class IncidentEngineTest {
    private val clock = MutableClock()
    private val engine = IncidentEngine(listOf(DownstreamLatencyRule(settings, Duration.ofMillis(500))), clock)
    private val transitions = mutableListOf<Transition>()

    /** Advances [seconds] in 5s evaluation steps, recording 20 calls of [millis] each step. */
    private fun run(seconds: Int, millis: Long) {
        repeat(seconds / 5) {
            repeat(20) { i -> engine.record(ok(clock.now.plusMillis(i * 250L), millis)) }
            clock.advance(Duration.ofSeconds(5))
            transitions += engine.evaluate()
        }
    }

    private fun states() = transitions.map { it.to }
    private fun state() = engine.snapshot().single().state

    @Test
    fun `healthy traffic never leaves NORMAL`() {
        run(120, 40)
        assertEquals(emptyList<IncidentState>(), states())
        assertEquals(IncidentState.NORMAL, state())
    }

    @Test
    fun `full lifecycle NORMAL - DEGRADED - FIRING - RECOVERED`() {
        run(30, 40)
        run(5, 1600)
        assertEquals(listOf(IncidentState.DEGRADED), states())
        run(25, 1600)
        assertEquals(listOf(IncidentState.DEGRADED), states(), "fires only after the firing duration")
        run(5, 1600)
        assertEquals(listOf(IncidentState.DEGRADED, IncidentState.FIRING), states())
        val firing = transitions.last()
        assertNotNull(firing.incidentId)

        // The slow samples leave the 20s window, then the condition must stay clear for 30s.
        run(45, 40)
        assertEquals(IncidentState.FIRING, state())
        run(5, 40)
        assertEquals(listOf(IncidentState.DEGRADED, IncidentState.FIRING, IncidentState.RECOVERED), states())
        assertEquals(firing.incidentId, transitions.last().incidentId)
        assertEquals(firing.firingSince, transitions.last().firingSince)
    }

    @Test
    fun `short spike goes back to NORMAL without firing`() {
        run(30, 40)
        run(5, 1600)
        run(60, 40)
        assertEquals(listOf(IncidentState.DEGRADED, IncidentState.NORMAL), states())
    }

    @Test
    fun `a continuing incident fires exactly once`() {
        run(30, 40)
        run(300, 1600)
        assertEquals(1, transitions.count { it.to == IncidentState.FIRING })
        assertEquals(IncidentState.FIRING, state())
    }

    @Test
    fun `relapse during recovery restarts the recovery timer instead of flapping`() {
        run(40, 1600)
        assertEquals(IncidentState.FIRING, state())
        run(25, 40) // window drained, recovery timer running for 5s
        run(5, 1600) // relapse
        run(25, 40) // without the reset the incident would have resolved here
        assertEquals(IncidentState.FIRING, state())
        assertEquals(1, transitions.count { it.to == IncidentState.FIRING })
        run(30, 40)
        assertEquals(IncidentState.RECOVERED, state())
        assertEquals(1, transitions.count { it.to == IncidentState.RECOVERED })
    }

    @Test
    fun `missing traffic neither fires nor resolves`() {
        run(40, 1600)
        assertEquals(IncidentState.FIRING, state())
        repeat(24) {
            clock.advance(Duration.ofSeconds(5))
            transitions += engine.evaluate()
        }
        assertEquals(IncidentState.FIRING, state())
    }

    @Test
    fun `recovered incident can fire again as a new incident`() {
        run(40, 1600)
        run(60, 40)
        assertEquals(IncidentState.RECOVERED, state())
        clock.advance(Duration.ofSeconds(1))
        run(40, 1600)
        val fired = transitions.filter { it.to == IncidentState.FIRING }
        assertEquals(2, fired.size)
        assertNotEquals(fired[0].incidentId, fired[1].incidentId)
    }

    @Test
    fun `inbound and outbound rules each see only their own samples`() {
        val both = IncidentEngine(
            listOf(DownstreamLatencyRule(settings, Duration.ofMillis(500)), ServiceLatencyRule(settings, Duration.ofMillis(250))),
            clock,
        )
        val fired = mutableListOf<Transition>()
        // The service answers slowly while its downstream calls stay fast: only service-latency fires.
        repeat(12) {
            repeat(20) { i ->
                val at = clock.now.plusMillis(i * 250L)
                both.record(ok(at, 5))
                both.record(request(at, 400))
            }
            clock.advance(Duration.ofSeconds(5))
            fired += both.evaluate()
        }
        assertEquals(
            setOf("service-latency" to "GET /api/quote", "downstream-latency" to "pricing"),
            both.snapshot().map { it.rule to it.dependency }.toSet(),
            "one key per rule and direction, no cross-over keys",
        )
        val firing = fired.filter { it.to == IncidentState.FIRING }
        assertEquals(listOf("service-latency"), firing.map { it.key.rule })
        assertEquals("KubeGuardServiceLatencyHigh", firing.single().alertName)
    }
}
