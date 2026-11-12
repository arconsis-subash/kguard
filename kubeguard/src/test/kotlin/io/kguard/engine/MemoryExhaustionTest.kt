package io.kguard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Heap reports every 10s like the Collector's scrape. 256 MiB heap; garbage collection makes the used heap go up and
 * down (a sawtooth of 13 MiB on top of the floor, collected every 20s: each bucket sees the floor once). Window 2m, buckets 20s, 4 floors needed, from 40% full,
 * predicted full within 5m, firing 20s, recovery 30s.
 */
class MemoryExhaustionTest {
    private val clock = MutableClock()
    private val rule = MemoryExhaustionRule(
        RuleSettings(Duration.ofMinutes(2), 1, Duration.ofSeconds(20), Duration.ofSeconds(30), "warning"),
        bucket = Duration.ofSeconds(20), minBuckets = 4, minFraction = 0.4,
        predictWithin = Duration.ofMinutes(5), staleAfter = Duration.ofSeconds(40),
    )
    private val engine = PodHealthEngine(listOf(rule), clock, Duration.ofSeconds(40))
    private val transitions = mutableListOf<Transition>()
    private val mib = 1024.0 * 1024.0
    private val max = 256 * mib

    /** Used heap: [floorMib] plus the sawtooth of young-generation garbage for this [step]. */
    private fun heap(pod: String, floorMib: Double, step: Int) =
        HeapSample(clock.now, "backend", pod, (floorMib + (step % 2) * 13) * mib, max)

    private fun run(seconds: Int, pods: (Int) -> List<HeapSample>) {
        repeat(seconds / 10) { step ->
            engine.record(pods(step))
            clock.advance(Duration.ofSeconds(10))
            transitions += engine.evaluate()
        }
    }

    private fun state() = engine.snapshot().single().state

    @Test
    fun `a steady heap never fires, however full`() {
        run(300) { step -> listOf(heap("backend-6dd56f6c7c-aaaaa", 200.0, step), heap("backend-6dd56f6c7c-bbbbb", 60.0, step)) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING })
        assertEquals(IncidentState.NORMAL, state())
    }

    @Test
    fun `a leak in both pods is predicted before the heap is full, and fresh pods recover`() {
        // ~1 MiB/s from 30 MiB: 40% (102 MiB) after ~72s, full after ~226s.
        var t = 0
        run(150) { step -> t = step * 10; listOf(heap("backend-6dd56f6c7c-aaaaa", 30.0 + t, step), heap("backend-6dd56f6c7c-bbbbb", 28.0 + t, step)) }
        assertEquals(IncidentState.FIRING, state())
        val fired = transitions.single { it.to == IncidentState.FIRING }
        assertTrue(fired.evaluation.explanation.startsWith("2 of 2 pods will run out of heap within 5m"), fired.evaluation.explanation)
        assertTrue(fired.evaluation.explanation.contains("rising 1.0 MiB/s"), fired.evaluation.explanation)
        assertTrue(fired.evaluation.observed!! in 60.0..200.0, "seconds left: ${fired.evaluation.observed}")
        assertTrue(fired.evaluation.byInstance!!.startsWith("backend-6dd56f6c7c-aaaaa: heap "), fired.evaluation.byInstance)
        val firedAt = transitions.indexOf(fired)
        assertTrue(t < 226, "fired at ${t}s, before the heap would be full")

        // Rollout restart: new pods start low and the old ones stop reporting.
        run(90) { step -> listOf(heap("backend-7f7f7f7f7f-ccccc", 30.0, step), heap("backend-7f7f7f7f7f-ddddd", 30.0, step)) }
        assertEquals(IncidentState.RECOVERED, state())
        assertEquals(1, transitions.count { it.to == IncidentState.FIRING }, "fired once (transition #$firedAt)")
    }

    @Test
    fun `growth that is far from filling the heap in time does not fire`() {
        // 0.2 MiB/s at 50%: full only in ~10 min.
        run(240) { step -> listOf(heap("backend-6dd56f6c7c-aaaaa", 128.0 + step * 2, step)) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING })
    }

    @Test
    fun `a fast leak still below the minimum share waits`() {
        run(40) { step -> listOf(heap("backend-6dd56f6c7c-aaaaa", 20.0 + step * 10, step)) }
        assertTrue(transitions.none { it.to == IncidentState.FIRING }, "at most 50 MiB (20%), below 40%")
    }

    @Test
    fun `fresh pods below the minimum share end the incident before they have enough reports`() {
        var t = 0
        run(150) { step -> t = step * 10; listOf(heap("backend-6dd56f6c7c-aaaaa", 30.0 + t, step)) }
        assertEquals(IncidentState.FIRING, state())
        // 40s after the restart: the old pod is stale, the new one has only 2 floors but 12% of the heap.
        run(40) { step -> listOf(heap("backend-7f7f7f7f7f-ccccc", 30.0, step)) }
        assertEquals(Verdict.NOT_MET, engine.snapshot().single().lastEvaluation!!.verdict)
        assertTrue(engine.snapshot().single().lastEvaluation!!.explanation.startsWith("no pod at risk"))
        run(40) { step -> listOf(heap("backend-7f7f7f7f7f-ccccc", 30.0, step)) }
        assertEquals(IncidentState.RECOVERED, state())
    }

    @Test
    fun `too few reports are insufficient data`() {
        run(30) { step -> listOf(heap("backend-6dd56f6c7c-aaaaa", 200.0 + step * 10, step)) }
        assertEquals(Verdict.INSUFFICIENT_DATA, engine.snapshot().single().lastEvaluation!!.verdict)
    }
}
