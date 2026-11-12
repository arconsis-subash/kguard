package io.kguard.ingest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** Shape as sent by the k8s_cluster receiver through otlp_http with encoding json. */
class OtlpJsonMetricsTest {
    private val json = """
        {"resourceMetrics":[{"resource":{"attributes":[
            {"key":"k8s.namespace.name","value":{"stringValue":"my-namespace"}},
            {"key":"k8s.pod.name","value":{"stringValue":"my-app-76dbcf4d9d-w587d"}},
            {"key":"k8s.container.name","value":{"stringValue":"my-app"}},
            {"key":"container.image.name","value":{"stringValue":"registry.example.com/my-team/my-app"}},
            {"key":"container.image.tag","value":{"stringValue":"sha-5e3987f"}},
            {"key":"k8s.container.status.last_terminated_reason","value":{"stringValue":"Error"}}]},
          "scopeMetrics":[{"metrics":[
            {"name":"k8s.container.restarts","gauge":{"dataPoints":[{"asInt":"4"}]}},
            {"name":"k8s.container.ready","gauge":{"dataPoints":[{"asInt":"0"}]}},
            {"name":"k8s.container.status.reason","sum":{"dataPoints":[
              {"asInt":"0","attributes":[{"key":"k8s.container.status.reason","value":{"stringValue":"ContainerCreating"}}]},
              {"asInt":"1","attributes":[{"key":"k8s.container.status.reason","value":{"stringValue":"CrashLoopBackOff"}}]}]}}]}]},
         {"resource":{"attributes":[
            {"key":"k8s.pod.name","value":{"stringValue":"my-app-postgresql-0"}},
            {"key":"k8s.container.name","value":{"stringValue":"postgresql"}}]},
          "scopeMetrics":[{"metrics":[{"name":"k8s.container.restarts","gauge":{"dataPoints":[{"asInt":"0"}]}}]}]}]}
    """.trimIndent()

    @Test
    fun `reads restarts, readiness, current reason and derives the workload`() {
        val states = OtlpJsonMetrics.parse(jacksonObjectMapper().readTree(json), Instant.EPOCH)
        val backend = states[0]
        assertEquals("my-app", backend.workload)
        assertEquals("my-app-76dbcf4d9d", backend.replicaSet)
        assertEquals(4L, backend.restarts)
        assertEquals(false, backend.ready)
        assertEquals("CrashLoopBackOff", backend.reason)
        assertEquals("Error", backend.lastTerminatedReason)
        assertEquals("my-app:sha-5e3987f", backend.image)
        assertEquals("my-app-postgresql", states[1].workload)
        assertEquals(null, states[1].reason)
    }
}
