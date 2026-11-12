package io.kguard.ingest

import io.kguard.KubeGuard
import com.fasterxml.jackson.databind.JsonNode
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** OTLP/HTTP metrics receiver (JSON encoding only), for the Collector's pod-state pipeline `metrics/kubeguard`. */
@Path("/v1/metrics")
class OtlpMetricsResource(private val guard: KubeGuard) {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun export(request: JsonNode): Map<String, Any> {
        guard.ingestMetrics(request)
        return emptyMap()
    }
}
