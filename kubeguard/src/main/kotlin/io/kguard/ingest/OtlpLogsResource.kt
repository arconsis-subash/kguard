package io.kguard.ingest

import io.kguard.KubeGuard
import com.fasterxml.jackson.databind.JsonNode
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** OTLP/HTTP logs receiver (JSON encoding only), for the Collector's pod-object pipeline `logs/kubeguard`. */
@Path("/v1/logs")
class OtlpLogsResource(private val guard: KubeGuard) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun export(request: JsonNode): Map<String, Any> {
        guard.ingestPods(request)
        return emptyMap()
    }
}
