package io.kguard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Reports every 10s like the k8sobjects receiver. Stuck after 60s, firing 30s, recovery 30s, stale after 40s. */
class PodStuckTest {
    private val clock = MutableClock()
    private val ruleSettings = RuleSettings(Duration.ofMinutes(2), 1, Duration.ofSeconds(30), Duration.ofSeconds(30), "warning")
    private fun rule(stuck: PodStuckRule.Stuck) =
        PodStuckRule(ruleSettings, stuck, stuckAfter = Duration.ofSeconds(60), staleAfter = Duration.ofSeconds(40))
    private val engine = PodHealthEngine(
        listOf(rule(PodStuckRule.Stuck.PENDING), rule(PodStuckRule.Stuck.TERMINATING)), clock, Duration.ofSeconds(40),
    )
    private val transitions = mutableListOf<Transition>()
    private val start = clock.now

    private fun pod(
        name: String, workload: String = name, phase: String = "Running", createdAt: Instant = start.minusSeconds(3600),
        deletionAt: Instant? = null, finalizers: List<String> = emptyList(), reason: String? = null,
    ) = PodObject(clock.now, "ns", workload, name, phase, createdAt, deletionAt, finalizers, reason)

    private fun run(seconds: Int, pods: () -> List<PodObject>) {
        repeat(seconds / 10) {
            engine.record(pods())
            clock.advance(Duration.ofSeconds(10))
            transitions += engine.evaluate()
        }
    }

    private fun state(rule: String, workload: String) =
        engine.snapshot().single { it.rule == rule && it.service == workload }.state

    @Test
    fun `running pods never fire`() {
        run(300) { listOf(pod("backend-6dd56f6c7c-aaaaa", "backend"), pod("backend-6dd56f6c7c-bbbbb", "backend")) }
        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `a pod pending only briefly (normal rollout) does not fire`() {
        run(40) { listOf(pod("backend-7f-ccccc", "backend", phase = "Pending", createdAt = start)) }
        run(200) { listOf(pod("backend-7f-ccccc", "backend", createdAt = start)) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING })
    }

    @Test
    fun `a pod pending past stuck-after fires, and recovers once it is deleted`() {
        val healthy = pod("backend-6dd56f6c7c-aaaaa", "backend")
        run(100) {
            listOf(healthy.copy(receivedAt = clock.now),
                pod("stuck-pending", phase = "Pending", createdAt = start, reason = "Unschedulable: 0/3 nodes are available"))
        }
        assertEquals(IncidentState.FIRING, state("pod-pending", "stuck-pending"))
        val fired = transitions.single { it.to == IncidentState.FIRING }
        assertEquals("KubeGuardPodStuckPending", fired.alertName)
        assertTrue(fired.evaluation.explanation.contains("Unschedulable"), fired.evaluation.explanation)
        assertEquals(IncidentState.NORMAL, state("pod-terminating", "stuck-pending"))

        // Deleted: only the healthy pod still reports. Gone after stale-after, resolved after recovery-duration.
        run(90) { listOf(healthy.copy(receivedAt = clock.now)) }
        assertEquals(IncidentState.RECOVERED, state("pod-pending", "stuck-pending"))
    }

    @Test
    fun `a pod held by a finalizer past its grace period fires`() {
        run(110) {
            listOf(pod("stuck-terminating", deletionAt = start, finalizers = listOf("example.com/hold")))
        }
        assertEquals(IncidentState.FIRING, state("pod-terminating", "stuck-terminating"))
        val fired = transitions.single { it.to == IncidentState.FIRING }
        assertTrue(fired.evaluation.explanation.contains("example.com/hold"), fired.evaluation.explanation)
        assertTrue(fired.evaluation.byInstance!!.contains("stuck-terminating"))
    }

    @Test
    fun `a silent source does not resolve a firing incident`() {
        run(110) { listOf(pod("stuck-terminating", deletionAt = start)) }
        assertEquals(IncidentState.FIRING, state("pod-terminating", "stuck-terminating"))
        run(110) { emptyList() }
        assertEquals(IncidentState.FIRING, state("pod-terminating", "stuck-terminating"))
    }
}
