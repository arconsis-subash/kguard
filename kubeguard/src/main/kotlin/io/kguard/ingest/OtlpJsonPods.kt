package io.kguard.ingest

import io.kguard.engine.PodObject
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.time.Instant

/**
 * Reads the Pod objects of the k8sobjects receiver from an OTLP/HTTP JSON `ExportLogsServiceRequest`: one log record
 * per object, the object itself as the body (pull mode), or `{type, object}` (watch mode). One [PodObject] per pod
 * and request; objects of another kind are skipped.
 *
 * Workload: the owning ReplicaSet's Deployment (`<deployment>-<hash>`), else the owner itself (StatefulSet, Job …),
 * else the pod name for a bare pod. Same names as [OtlpJsonMetrics] derives for the crash-loop rule.
 */
object OtlpJsonPods {
    private val replicaSet = Regex("^(.+)-([a-z0-9]{6,10})$")

    fun parse(request: JsonNode, receivedAt: Instant): List<PodObject> {
        val pods = mutableListOf<PodObject>()
        for (resourceLogs in request.path("resourceLogs")) {
            for (scope in resourceLogs.path("scopeLogs")) {
                for (record in scope.path("logRecords")) {
                    val body = toJson(record.path("body"))
                    val obj = if (body.has("object")) body.path("object") else body
                    // The receiver only pulls pods; a list item may come without its kind.
                    if (obj.path("kind").asText("Pod").ifEmpty { "Pod" } != "Pod") continue
                    pods += pod(obj, receivedAt) ?: continue
                }
            }
        }
        return pods
    }

    private fun pod(obj: JsonNode, receivedAt: Instant): PodObject? {
        val meta = obj.path("metadata")
        val name = meta.path("name").textValue() ?: return null
        val status = obj.path("status")
        val owner = meta.path("ownerReferences").firstOrNull { it.path("controller").asBoolean(false) }
            ?: meta.path("ownerReferences").firstOrNull()
        val workload = when (owner?.path("kind")?.asText()) {
            null -> name
            "ReplicaSet" -> owner.path("name").asText().let { replicaSet.matchEntire(it)?.groupValues?.get(1) ?: it }
            else -> owner.path("name").asText()
        }
        return PodObject(
            receivedAt = receivedAt,
            namespace = meta.path("namespace").asText(),
            workload = workload,
            pod = name,
            phase = status.path("phase").textValue(),
            createdAt = instant(meta.path("creationTimestamp")),
            deletionAt = instant(meta.path("deletionTimestamp")),
            finalizers = meta.path("finalizers").mapNotNull { it.textValue() },
            reason = reason(status),
        )
    }

    /** Unschedulable condition, else the first waiting container's reason, else the pod's own status reason. */
    private fun reason(status: JsonNode): String? {
        status.path("conditions")
            .firstOrNull { it.path("type").asText() == "PodScheduled" && it.path("status").asText() == "False" }
            ?.let { return listOfNotNull(it.path("reason").textValue(), it.path("message").textValue()).joinToString(": ") }
        (status.path("initContainerStatuses") + status.path("containerStatuses"))
            .map { it.path("state").path("waiting") }
            .firstOrNull { it.path("reason").textValue() != null }
            ?.let { w ->
                return listOfNotNull(w.path("reason").textValue(), w.path("message").textValue()?.take(200)).joinToString(": ")
            }
        return status.path("reason").textValue()
    }

    private operator fun JsonNode.plus(other: JsonNode): List<JsonNode> = toList() + other.toList()

    private fun instant(node: JsonNode): Instant? = node.textValue()?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** OTLP JSON `AnyValue` to plain JSON. */
    internal fun toJson(value: JsonNode): JsonNode {
        val f = JsonNodeFactory.instance
        return when {
            value.has("kvlistValue") -> f.objectNode().apply {
                value.path("kvlistValue").path("values").forEach { set<JsonNode>(it.path("key").asText(), toJson(it.path("value"))) }
            }
            value.has("arrayValue") -> f.arrayNode().apply { value.path("arrayValue").path("values").forEach { add(toJson(it)) } }
            value.has("stringValue") -> f.textNode(value.path("stringValue").asText())
            value.has("boolValue") -> f.booleanNode(value.path("boolValue").asBoolean())
            value.has("intValue") -> f.numberNode(value.path("intValue").asLong())
            value.has("doubleValue") -> f.numberNode(value.path("doubleValue").asDouble())
            else -> f.nullNode()
        }
    }
}
