package io.kguard.ingest

import com.fasterxml.jackson.databind.JsonNode

/** The fields of one OTLP span that KubeGuard looks at. */
data class ParsedSpan(
    val traceId: String,
    val service: String,
    /** Resource attribute `host.name` (the pod name in Kubernetes), else `service.instance.id`. */
    val instance: String?,
    val name: String,
    val kind: Int,
    val durationNanos: Long,
    val statusError: Boolean,
    val attributes: Map<String, String>,
    /** Type and message of the span's first `exception` event. */
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
) {
    val isClient get() = kind == SPAN_KIND_CLIENT
    val isServer get() = kind == SPAN_KIND_SERVER

    companion object {
        const val SPAN_KIND_SERVER = 2
        const val SPAN_KIND_CLIENT = 3
    }
}

/**
 * Reads an OTLP/HTTP JSON `ExportTraceServiceRequest` (https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding).
 * Accepts enums as numbers (spec) or names, and 64-bit integers as strings (spec) or numbers. Only SERVER and
 * CLIENT spans are returned.
 */
object OtlpJsonSpans {

    private const val STATUS_CODE_ERROR = 2

    fun parse(request: JsonNode): List<ParsedSpan> {
        val spans = mutableListOf<ParsedSpan>()
        for (resourceSpans in request.path("resourceSpans")) {
            val resource = attributes(resourceSpans.path("resource").path("attributes"))
            val service = resource["service.name"] ?: "unknown"
            val instance = resource["host.name"] ?: resource["service.instance.id"]
            for (scopeSpans in resourceSpans.path("scopeSpans")) {
                for (span in scopeSpans.path("spans")) {
                    val kind = enumValue(span.path("kind"), "SPAN_KIND_") ?: continue
                    if (kind != ParsedSpan.SPAN_KIND_CLIENT && kind != ParsedSpan.SPAN_KIND_SERVER) continue
                    spans += toParsed(span, service, instance, kind) ?: continue
                }
            }
        }
        return spans
    }

    private fun toParsed(span: JsonNode, service: String, instance: String?, kind: Int): ParsedSpan? {
        val start = span.path("startTimeUnixNano").asText().toLongOrNull() ?: return null
        val end = span.path("endTimeUnixNano").asText().toLongOrNull() ?: return null
        val exception = span.path("events").firstOrNull { it.path("name").asText() == "exception" }
            ?.let { attributes(it.path("attributes")) }
        return ParsedSpan(
            traceId = span.path("traceId").asText(""),
            service = service,
            instance = instance,
            name = span.path("name").asText("unknown"),
            kind = kind,
            durationNanos = (end - start).coerceAtLeast(0),
            statusError = enumValue(span.path("status").path("code"), "STATUS_CODE_") == STATUS_CODE_ERROR,
            attributes = attributes(span.path("attributes")),
            exceptionType = exception?.get("exception.type"),
            exceptionMessage = exception?.get("exception.message"),
        )
    }

    /** OTLP `KeyValue` list to a flat map of scalar values as strings. */
    private fun attributes(list: JsonNode): Map<String, String> = list.mapNotNull { kv ->
        val value = kv.path("value")
        val scalar = listOf("stringValue", "intValue", "doubleValue", "boolValue")
            .firstNotNullOfOrNull { field -> value.get(field)?.asText() }
        scalar?.let { kv.path("key").asText() to it }
    }.toMap()

    private val enumNames = mapOf(
        "SPAN_KIND_UNSPECIFIED" to 0, "SPAN_KIND_INTERNAL" to 1, "SPAN_KIND_SERVER" to 2, "SPAN_KIND_CLIENT" to 3,
        "SPAN_KIND_PRODUCER" to 4, "SPAN_KIND_CONSUMER" to 5,
        "STATUS_CODE_UNSET" to 0, "STATUS_CODE_OK" to 1, "STATUS_CODE_ERROR" to 2,
    )

    private fun enumValue(node: JsonNode, prefix: String): Int? = when {
        node.isNumber -> node.asInt()
        node.isTextual -> node.asText().toIntOrNull() ?: enumNames[node.asText()]?.takeIf {
            node.asText().startsWith(prefix)
        }
        else -> null
    }
}
