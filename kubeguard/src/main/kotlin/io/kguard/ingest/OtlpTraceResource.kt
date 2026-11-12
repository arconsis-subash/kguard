package io.kguard.ingest

import io.kguard.KubeGuard
import com.fasterxml.jackson.databind.JsonNode
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/**
 * OTLP/HTTP trace receiver (JSON encoding only). The OTel Collector's `otlphttp` exporter posts here with
 * `encoding: json`; it appends `/v1/traces` to its endpoint.
 */
@Path("/v1/traces")
class OtlpTraceResource(private val guard: KubeGuard) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun export(request: JsonNode): Map<String, Any> {
        guard.ingest(request)
        // An empty ExportTraceServiceResponse: everything accepted.
        return emptyMap()
    }
}
