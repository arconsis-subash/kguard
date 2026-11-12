package io.kguard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reports every 10s like the k8s_cluster receiver. Window 5m, min 2 restarts, firing 30s, recovery 60s. */
class PodHealthTest {
    private val clock = MutableClock()
    private val rule = PodCrashLoopRule(
        RuleSettings(Duration.ofMinutes(5), 1, Duration.ofSeconds(30), Duration.ofSeconds(60), "critical"),
        minRestarts = 2, staleAfter = Duration.ofSeconds(40),
    )
    private val engine = PodHealthEngine(listOf(rule), clock, Duration.ofSeconds(40))
    private val transitions = mutableListOf<Transition>()

    private fun state(pod: String, restarts: Long, reason: String? = null, rs: String = "backend-6dd56f6c7c") =
        ContainerState(clock.now, "ns", "backend", rs, pod, "app", "backend:sha-1", restarts, reason == null, reason, null)

    /** [seconds] in 10s steps; [pods] gives the reports of one step from its index. */
    private fun run(seconds: Int, pods: (Int) -> List<ContainerState>) {
        repeat(seconds / 10) { step ->
            engine.record(pods(step))
            clock.advance(Duration.ofSeconds(10))
            transitions += engine.evaluate()
        }
    }

    private fun state() = engine.snapshot().single().state

    @Test
    fun `healthy pods never fire`() {
        run(300) { listOf(state("backend-6dd56f6c7c-aaaaa", 0), state("backend-6dd56f6c7c-bbbbb", 1)) }
        assertTrue(transitions.isEmpty())
        assertEquals(IncidentState.NORMAL, state())
    }

    @Test
    fun `repeated restarts fire, and a removed pod (rollback) recovers`() {
        var restarts = 0L
        run(120) { step ->
            if (step % 3 == 0) restarts++
            listOf(state("backend-6dd56f6c7c-aaaaa", 0), state("backend-7f7f7f7f7f-ccccc", restarts, rs = "backend-7f7f7f7f7f"))
        }
        assertEquals(IncidentState.FIRING, state())
        val fired = transitions.single { it.to == IncidentState.FIRING }
        assertTrue(fired.evaluation.explanation.contains("backend-7f7f7f7f7f-ccccc"), fired.evaluation.explanation)
        assertTrue(fired.evaluation.byInstance!!.contains("ReplicaSet backend-7f7f7f7f7f"))

        // Rollback: the crashing pod is gone, only the old one reports.
        run(60) { listOf(state("backend-6dd56f6c7c-aaaaa", 0)) }
        assertEquals(IncidentState.FIRING, state(), "recovery duration not over yet")
        run(60) { listOf(state("backend-6dd56f6c7c-aaaaa", 0)) }
        assertEquals(IncidentState.RECOVERED, state())
        assertEquals(1, transitions.count { it.to == IncidentState.FIRING })
    }

    @Test
    fun `waiting in CrashLoopBackOff is enough`() {
        run(60) { listOf(state("backend-7f7f7f7f7f-ccccc", 1, reason = "CrashLoopBackOff")) }
        assertEquals(IncidentState.FIRING, state())
    }

    @Test
    fun `a single restart does not fire`() {
        run(30) { listOf(state("backend-6dd56f6c7c-aaaaa", 0)) }
        run(270) { listOf(state("backend-6dd56f6c7c-aaaaa", 1)) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING })
    }
}
