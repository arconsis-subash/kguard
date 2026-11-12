package io.kguard.ingest

import io.kguard.engine.HeapSample
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * Reads the JVM heap of each pod from an OTLP/HTTP JSON `ExportMetricsServiceRequest`: the Micrometer gauges
 * `jvm_memory_used_bytes` and `jvm_memory_max_bytes` (as the Collector's prometheus receiver passes them on; also
 * accepted without the `_bytes` suffix), data points with `area=heap`, one per heap pool (`id`). One [HeapSample] per
 * pod and request: used and max summed over the pools; pools without a maximum (-1, e.g. G1 Eden) add nothing to max.
 *
 * The pod comes from the data point attribute `pod` (the backend tags its metrics with its HOSTNAME), the workload
 * from the pod name like [OtlpJsonMetrics]. Data points without a pod are skipped.
 */
object OtlpJsonHeap {
    private val used = setOf("jvm_memory_used_bytes", "jvm_memory_used")
    private val max = setOf("jvm_memory_max_bytes", "jvm_memory_max")

    fun parse(request: JsonNode, receivedAt: Instant): List<HeapSample> {
        data class Sum(var used: Double = 0.0, var max: Double = 0.0, var seen: Boolean = false)
        val byPod = LinkedHashMap<String, Sum>()
        for (resourceMetrics in request.path("resourceMetrics")) {
            for (scope in resourceMetrics.path("scopeMetrics")) {
                for (metric in scope.path("metrics")) {
                    val name = metric.path("name").asText()
                    val isUsed = name in used
                    if (!isUsed && name !in max) continue
                    for (point in metric.path("gauge").path("dataPoints")) {
                        val attrs = OtlpJsonMetrics.attributes(point.path("attributes"))
                        if (attrs["area"] != "heap") continue
                        val pod = attrs["pod"]?.takeIf { it.isNotBlank() } ?: continue
                        val value = OtlpJsonMetrics.value(point) ?: continue
                        val sum = byPod.getOrPut(pod) { Sum() }
                        if (isUsed) {
                            sum.used += value
                            sum.seen = true
                        } else if (value > 0) {
                            sum.max += value
                        }
                    }
                }
            }
        }
        return byPod.filterValues { it.seen && it.max > 0 }.map { (pod, sum) ->
            HeapSample(receivedAt, OtlpJsonMetrics.workloadOf(pod), pod, sum.used, sum.max)
        }
    }
}
