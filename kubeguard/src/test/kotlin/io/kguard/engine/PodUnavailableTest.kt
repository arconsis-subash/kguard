package io.kguard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reports every 10s like the k8s_cluster receiver. Window 2m, firing 30s, recovery 30s, stale after 40s. */
class PodUnavailableTest {
    private val clock = MutableClock()
    private val rule = PodUnavailableRule(
        RuleSettings(Duration.ofMinutes(2), 1, Duration.ofSeconds(30), Duration.ofSeconds(30), "critical"),
        staleAfter = Duration.ofSeconds(40),
    )
    private val engine = PodHealthEngine(listOf(rule), clock, Duration.ofSeconds(40))
    private val transitions = mutableListOf<Transition>()

    private fun pod(name: String, ready: Boolean, reason: String? = null, workload: String = "backend") =
        ContainerState(clock.now, "ns", workload, "$workload-6dd56f6c7c", name, "app", "backend:sha-1", 0, ready, reason, null)

    private fun run(seconds: Int, pods: (Int) -> List<ContainerState>) {
        repeat(seconds / 10) { step ->
            engine.record(pods(step))
            clock.advance(Duration.ofSeconds(10))
            transitions += engine.evaluate()
        }
    }

    private fun state(workload: String = "backend") = engine.snapshot().single { it.service == workload }.state

    @Test
    fun `ready pods never fire`() {
        run(180) { listOf(pod("backend-6dd56f6c7c-aaaaa", true), pod("backend-6dd56f6c7c-bbbbb", true)) }
        assertTrue(transitions.isEmpty())
        assertEquals(IncidentState.NORMAL, state())
    }

    @Test
    fun `no ready pod fires, and ready pods after a restart recover`() {
        run(60) { listOf(pod("backend-6dd56f6c7c-aaaaa", false), pod("backend-6dd56f6c7c-bbbbb", false)) }
        assertEquals(IncidentState.FIRING, state())
        val fired = transitions.single { it.to == IncidentState.FIRING }
        assertEquals("KubeGuardWorkloadUnavailable", rule.alertName)
        assertTrue(fired.evaluation.explanation.startsWith("0 of 2 pods ready"), fired.evaluation.explanation)
        assertEquals("backend-6dd56f6c7c-aaaaa: not ready; backend-6dd56f6c7c-bbbbb: not ready", fired.evaluation.byInstance)

        // Rollout restart: the new pods become ready, the old ones disappear (their reports go stale).
        run(60) { listOf(pod("backend-7f7f7f7f7f-ccccc", true), pod("backend-7f7f7f7f7f-ddddd", true)) }
        assertEquals(IncidentState.RECOVERED, state())
        assertEquals(1, transitions.count { it.to == IncidentState.FIRING })
    }

    @Test
    fun `a rollout never fires while an old pod is still ready`() {
        run(120) { listOf(pod("backend-6dd56f6c7c-aaaaa", true), pod("backend-7f7f7f7f7f-ccccc", false, "ContainerCreating")) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING })
    }

    @Test
    fun `one pod starting slowly for less than the firing duration does not fire`() {
        run(20) { listOf(pod("backend-6dd56f6c7c-aaaaa", false, "ContainerCreating")) }
        run(60) { listOf(pod("backend-6dd56f6c7c-aaaaa", true)) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING })
    }

    @Test
    fun `finished Job pods are not an unavailable workload`() {
        // "db-password-rotation-x7k2p" looks like a Deployment pod of "db-password" to the name parser.
        run(120) { listOf(pod("db-password-rotation-x7k2p", false, "Completed", workload = "db-password")) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING })
        assertEquals(IncidentState.NORMAL, state("db-password"))
    }

    @Test
    fun `the reason of a pod that is not ready is in the evidence`() {
        run(60) { listOf(pod("backend-6dd56f6c7c-aaaaa", false, "CreateContainerConfigError")) }
        val fired = transitions.single { it.to == IncidentState.FIRING }
        assertTrue(fired.evaluation.explanation.contains("backend-6dd56f6c7c-aaaaa not ready (CreateContainerConfigError)"),
            fired.evaluation.explanation)
    }
}
