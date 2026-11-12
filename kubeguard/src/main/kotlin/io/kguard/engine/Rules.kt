package io.kguard.engine

import java.time.Duration
import kotlin.math.ceil

/**
 * "pod-a: <summary of its calls>; pod-b: ...", sorted by instance name, or null if no sample names its instance.
 * Lets the reader see whether one pod or all of them are affected.
 */
internal fun perInstance(samples: List<SpanSample>, summary: (List<SpanSample>) -> String): String? {
    val byInstance = samples.filter { it.instance != null }.groupBy { it.instance!! }
    if (byInstance.isEmpty()) return null
    return byInstance.toSortedMap().entries.joinToString("; ") { (instance, calls) -> "$instance: ${summary(calls)}" }
}

/** What every rule has, whatever it evaluates: the identity and settings the incident state machine needs. */
interface RuleInfo {
    /** Short identifier, used in the incident key and the `rule` label. */
    val name: String

    /** Alertmanager `alertname`. */
    val alertName: String

    val settings: RuleSettings
}

/** A deterministic incident condition over the calls from one service to one dependency. */
interface DetectionRule : RuleInfo {
    /** true: the rule evaluates the requests a service answered ([SpanSample.inbound]), false: the calls it made. */
    val inbound: Boolean get() = false

    /** [samples] are the calls of one (service, dependency) pair received within [RuleSettings.window], oldest first. */
    fun evaluate(samples: List<SpanSample>): Evaluation
}

/** p95 of the successful samples against [threshold]; [what] names them in the explanation, e.g. "calls". */
private fun p95Evaluation(samples: List<SpanSample>, settings: RuleSettings, threshold: Duration, what: String): Evaluation {
    val durations = samples.filterNot { it.error }.map { it.duration.toMillis() }.sorted()
    val limit = threshold.toMillis().toDouble()
    if (durations.size < settings.minSamples) {
        return Evaluation.insufficient(durations.size, settings, limit)
    }
    val p95 = DownstreamLatencyRule.percentile(durations, 0.95)
    return Evaluation(
        verdict = if (p95 > limit) Verdict.MET else Verdict.NOT_MET,
        sampleCount = durations.size,
        observed = p95.toDouble(),
        threshold = limit,
        explanation = "p95 latency of ${durations.size} successful $what in the last " +
            "${settings.window.toSeconds()}s is $p95 ms (threshold ${limit.toLong()} ms, max ${durations.last()} ms)",
        byInstance = perInstance(samples.filterNot { it.error }) { calls ->
            val sorted = calls.map { it.duration.toMillis() }.sorted()
            "p95 ${DownstreamLatencyRule.percentile(sorted, 0.95)} ms over ${sorted.size} $what"
        },
    )
}

/**
 * Downstream latency: the p95 duration of the *successful* calls to a dependency exceeds a threshold. Failed calls
 * are left out so that timeouts during a connectivity failure are not also reported as latency.
 */
class DownstreamLatencyRule(override val settings: RuleSettings, private val p95Threshold: Duration) : DetectionRule {
    override val name = NAME
    override val alertName = "KubeGuardDownstreamLatencyHigh"

    override fun evaluate(samples: List<SpanSample>) = p95Evaluation(samples, settings, p95Threshold, "calls")

    companion object {
        const val NAME = "downstream-latency"

        /** Nearest-rank percentile of an ascending list. */
        fun percentile(sorted: List<Long>, quantile: Double): Long =
            sorted[(ceil(quantile * sorted.size).toInt() - 1).coerceAtLeast(0)]
    }
}

/**
 * Service latency: the p95 duration of the *successful* requests one endpoint of a service answered (its SERVER
 * spans) exceeds a threshold. This is what the service's callers see. Together with [DownstreamLatencyRule] it
 * separates cause from symptom: a slow dependency fires both, a slow service with fast dependencies only this one.
 */
class ServiceLatencyRule(override val settings: RuleSettings, private val p95Threshold: Duration) : DetectionRule {
    override val name = NAME
    override val alertName = "KubeGuardServiceLatencyHigh"
    override val inbound = true

    override fun evaluate(samples: List<SpanSample>) = p95Evaluation(samples, settings, p95Threshold, "requests")

    companion object {
        const val NAME = "service-latency"
    }
}

/**
 * Dependency connectivity failure: the share of calls that failed *without any response* (connection refused,
 * connect/read timeout, unknown host) reaches a threshold. Calls answered with an HTTP error status reached the
 * dependency and do not count.
 */
class DependencyConnectivityRule(override val settings: RuleSettings, private val failureRatioThreshold: Double) :
    DetectionRule {
    override val name = NAME
    override val alertName = "KubeGuardDependencyConnectivityFailure"

    override fun evaluate(samples: List<SpanSample>): Evaluation {
        if (samples.size < settings.minSamples) {
            return Evaluation.insufficient(samples.size, settings, failureRatioThreshold)
        }
        val failures = samples.filter { it.connectivityFailure }
        val ratio = failures.size.toDouble() / samples.size
        val commonError = failures.mapNotNull { it.errorType }
            .groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.let { ", most common error: ${it.key}" } ?: ""
        return Evaluation(
            verdict = if (ratio >= failureRatioThreshold) Verdict.MET else Verdict.NOT_MET,
            sampleCount = samples.size,
            observed = ratio,
            threshold = failureRatioThreshold,
            explanation = "${failures.size} of ${samples.size} calls in the last ${settings.window.toSeconds()}s " +
                "(${pct(ratio)}) could not reach the dependency (threshold ${pct(failureRatioThreshold)})$commonError",
            byInstance = perInstance(samples) { calls ->
                "${calls.count { it.connectivityFailure }}/${calls.size} calls failed"
            },
        )
    }

    private fun pct(value: Double) = "%.0f%%".format(value * 100)

    companion object {
        const val NAME = "dependency-connectivity"
    }
}
