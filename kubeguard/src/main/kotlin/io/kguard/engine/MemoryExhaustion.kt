package io.kguard.engine

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Heap of one JVM pod as its Micrometer metrics report it (`jvm_memory_used_bytes` / `jvm_memory_max_bytes`, summed
 * over the heap pools), received at [receivedAt] by KubeGuard.
 */
data class HeapSample(
    override val receivedAt: Instant,
    override val workload: String,
    val pod: String,
    val usedBytes: Double,
    val maxBytes: Double,
) : WorkloadReport

/**
 * Memory exhaustion predicted: a pod's heap fills steadily and will be full soon. It fires *before* the JVM runs out
 * of memory, so the pods can be replaced one at a time instead of failing (often all at once, because replicas that
 * started together leak at the same rate).
 *
 * The heap goes up and down with every garbage collection, so the rule reads its floor: the lowest value in each
 * [bucket] of the window, which is what stays after collection. A least-squares line through the floors gives the
 * growth rate, and (max - current floor) / rate the time until the heap is full. Met when a current pod (reported
 * within [staleAfter]) has at least [minBuckets] floors, is at least [minFraction] full and will be full within
 * [predictWithin]. A pod whose floor does not grow is never predicted to run out, however full it is.
 *
 * Limitation: the floor is only meaningful when garbage is collected more often than once per [bucket]. A healthy
 * JVM whose young generation fills slowly (one collection every few minutes) looks like a rising heap until it is
 * collected; [minFraction] keeps such pods out while their heap is mostly free. In production, read the old
 * generation after collection (Micrometer `jvm_gc_live_data_size_bytes`) over a window of an hour instead.
 */
class MemoryExhaustionRule(
    override val settings: RuleSettings,
    private val bucket: Duration,
    private val minBuckets: Int,
    private val minFraction: Double,
    private val predictWithin: Duration,
    private val staleAfter: Duration,
) : WorkloadRule<HeapSample> {
    override val name = NAME
    override val alertName = "KubeGuardMemoryExhaustionPredicted"

    /** One pod's prediction. [secondsLeft] is null when the floor does not grow. */
    data class Forecast(val pod: String, val floorBytes: Double, val maxBytes: Double, val bytesPerSecond: Double,
                        val secondsLeft: Double?) {
        val fraction get() = floorBytes / maxBytes
    }

    override fun evaluate(history: List<HeapSample>, now: Instant, feedAlive: Boolean): Evaluation {
        val current = history.groupBy { it.pod }.filterValues { it.last().receivedAt.isAfter(now.minus(staleAfter)) }
        val forecasts = current.mapNotNull { (pod, samples) -> forecast(pod, samples, now) }.sortedBy { it.pod }
        // A pod without enough floors yet (just started, e.g. after a restart) cannot be at risk while its heap is below
        // minFraction, so it does not keep an incident open while it collects reports.
        val safe = current.filterKeys { pod -> forecasts.none { it.pod == pod } }
            .filterValues { it.last().usedBytes / it.last().maxBytes < minFraction }.keys
        if (forecasts.isEmpty() && safe.isNotEmpty()) {
            return Evaluation(Verdict.NOT_MET, safe.size, null, predictWithin.toSeconds().toDouble(),
                "no pod at risk: ${safe.sorted().joinToString(", ")} below ${(minFraction * 100).roundToLong()}% of the heap, still collecting reports")
        }
        if (forecasts.isEmpty()) {
            return Evaluation(Verdict.INSUFFICIENT_DATA, current.size, null, predictWithin.toSeconds().toDouble(),
                "not enough heap reports yet (need $minBuckets × ${bucket.toSeconds()}s per pod)")
        }
        val atRisk = forecasts.filter {
            it.secondsLeft != null && it.secondsLeft <= predictWithin.toSeconds() && it.fraction >= minFraction
        }
        val soonest = forecasts.mapNotNull { it.secondsLeft }.minOrNull()
        val within = "${predictWithin.toMinutes()}m"
        val explanation = if (atRisk.isNotEmpty()) {
            "${atRisk.size} of ${forecasts.size} pods will run out of heap within $within: " +
                atRisk.joinToString("; ") { describe(it) }
        } else {
            val fullest = forecasts.maxBy { it.fraction }
            "no pod will run out of heap within $within (fullest: ${describe(fullest)})"
        }
        return Evaluation(
            verdict = if (atRisk.isNotEmpty()) Verdict.MET else Verdict.NOT_MET,
            sampleCount = forecasts.size,
            observed = soonest,
            threshold = predictWithin.toSeconds().toDouble(),
            explanation = explanation,
            byInstance = forecasts.joinToString("; ") { "${it.pod}: ${describe(it, withPod = false)}" },
        )
    }

    private fun forecast(pod: String, samples: List<HeapSample>, now: Instant): Forecast? {
        val max = samples.last().maxBytes.takeIf { it > 0 } ?: return null
        // Floor per bucket, counted back from now so the newest bucket is always complete enough to compare.
        val floors = samples.groupBy { Duration.between(it.receivedAt, now).toMillis() / bucket.toMillis() }
            .map { (index, inBucket) -> -(index + 0.5) * bucket.toSeconds() to inBucket.minOf { it.usedBytes } }
            .sortedBy { it.first }
        if (floors.size < minBuckets) return null
        val meanX = floors.sumOf { it.first } / floors.size
        val meanY = floors.sumOf { it.second } / floors.size
        val slope = floors.sumOf { (x, y) -> (x - meanX) * (y - meanY) } / floors.sumOf { (x, _) -> (x - meanX) * (x - meanX) }
        val floor = floors.last().second
        val left = if (slope > 0) ((max - floor) / slope).coerceAtLeast(0.0) else null
        return Forecast(pod, floor, max, slope, left)
    }

    private fun describe(f: Forecast, withPod: Boolean = true): String {
        val mib = { bytes: Double -> (bytes / MIB).roundToLong() }
        val growth = if (f.secondsLeft == null) "not growing"
        else "rising %.1f MiB/s, full in ~%s".format(Locale.ROOT, f.bytesPerSecond / MIB, duration(f.secondsLeft))
        return (if (withPod) "${f.pod} " else "") +
            "heap ${mib(f.floorBytes)} of ${mib(f.maxBytes)} MiB (${(f.fraction * 100).roundToLong()}%), $growth"
    }

    private fun duration(seconds: Double): String {
        val s = seconds.roundToLong()
        return if (s >= 60) "${s / 60}m${"%02d".format(s % 60)}s" else "${s}s"
    }

    companion object {
        const val NAME = "memory-exhaustion"
        private const val MIB = 1024.0 * 1024.0
    }
}
