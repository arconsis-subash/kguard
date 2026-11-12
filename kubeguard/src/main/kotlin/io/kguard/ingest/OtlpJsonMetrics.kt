package io.kguard.ingest

import io.kguard.engine.ContainerState
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * Reads the container metrics of the k8s_cluster receiver from an OTLP/HTTP JSON `ExportMetricsServiceRequest`:
 * `k8s.container.restarts`, `k8s.container.ready` and `k8s.container.status.reason` (one data point per reason; the
 * current one is non-zero). One [ContainerState] per container and request.
 *
 * Workload: `k8s.deployment.name` if present, else derived from the pod name. A Deployment's pods are named
 * `<deployment>-<replicaset hash>-<suffix>`, a StatefulSet's `<statefulset>-<ordinal>`.
 */
object OtlpJsonMetrics {
    private val deploymentPod = Regex("^(.+)-([a-z0-9]{6,10})-([a-z0-9]{5})$")
    private val statefulSetPod = Regex("^(.+)-(\\d+)$")

    fun parse(request: JsonNode, receivedAt: Instant): List<ContainerState> {
        val states = mutableListOf<ContainerState>()
        for (resourceMetrics in request.path("resourceMetrics")) {
            val attrs = attributes(resourceMetrics.path("resource").path("attributes"))
            val pod = attrs["k8s.pod.name"] ?: continue
            val container = attrs["k8s.container.name"] ?: continue
            var restarts: Long? = null
            var ready: Boolean? = null
            var reason: String? = null
            for (scope in resourceMetrics.path("scopeMetrics")) {
                for (metric in scope.path("metrics")) {
                    val points = metric.path("gauge").path("dataPoints").takeIf { it.size() > 0 }
                        ?: metric.path("sum").path("dataPoints")
                    when (metric.path("name").asText()) {
                        "k8s.container.restarts" -> restarts = points.firstOrNull()?.let(::value)?.toLong()
                        "k8s.container.ready" -> ready = points.firstOrNull()?.let(::value)?.let { it > 0 }
                        "k8s.container.status.reason" -> reason = points
                            .firstOrNull { (value(it) ?: 0.0) > 0 }
                            ?.let { attributes(it.path("attributes"))["k8s.container.status.reason"] }
                    }
                }
            }
            val deployment = deploymentPod.matchEntire(pod)
            states += ContainerState(
                receivedAt = receivedAt,
                namespace = attrs["k8s.namespace.name"].orEmpty(),
                workload = attrs["k8s.deployment.name"] ?: workloadOf(pod),
                replicaSet = attrs["k8s.replicaset.name"]
                    ?: deployment?.let { "${it.groupValues[1]}-${it.groupValues[2]}" },
                pod = pod,
                container = container,
                image = listOfNotNull(attrs["container.image.name"]?.substringAfterLast('/'), attrs["container.image.tag"])
                    .takeIf { it.isNotEmpty() }?.joinToString(":"),
                restarts = restarts,
                ready = ready,
                reason = reason,
                lastTerminatedReason = attrs["k8s.container.status.last_terminated_reason"]?.takeIf { it.isNotBlank() },
            )
        }
        return states
    }

    /** Deployment or StatefulSet of a pod, from its name; a bare pod is its own workload. */
    fun workloadOf(pod: String): String =
        deploymentPod.matchEntire(pod)?.groupValues?.get(1) ?: statefulSetPod.matchEntire(pod)?.groupValues?.get(1) ?: pod

    internal fun value(point: JsonNode): Double? =
        point.get("asInt")?.asText()?.toDoubleOrNull() ?: point.get("asDouble")?.asDouble()

    internal fun attributes(list: JsonNode): Map<String, String> = list.mapNotNull { kv ->
        val value = kv.path("value")
        listOf("stringValue", "intValue", "doubleValue", "boolValue")
            .firstNotNullOfOrNull { field -> value.get(field)?.asText() }
            ?.let { kv.path("key").asText() to it }
    }.toMap()
}
