package io.kguard.ingest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** Shape as sent by the k8sobjects receiver (pods, pull mode) through otlp_http with encoding json. */
class OtlpJsonPodsTest {
    private fun s(v: String) = """{"stringValue":"$v"}"""
    private fun kv(vararg pairs: Pair<String, String>) =
        """{"kvlistValue":{"values":[${pairs.joinToString(",") { """{"key":"${it.first}","value":${it.second}}""" }}]}}"""
    private fun arr(vararg values: String) = """{"arrayValue":{"values":[${values.joinToString(",")}]}}"""

    private val pending = kv(
        "kind" to s("Pod"),
        "metadata" to kv("name" to s("stuck-pending"), "namespace" to s("my-namespace"),
            "creationTimestamp" to s("2026-10-02T12:00:00Z")),
        "status" to kv("phase" to s("Pending"), "conditions" to arr(kv("type" to s("PodScheduled"),
            "status" to s("False"), "reason" to s("Unschedulable"),
            "message" to s("0/3 nodes are available: 3 node(s) didn't match Pod's node affinity/selector.")))),
    )
    private val terminating = kv(
        "kind" to s("Pod"),
        "metadata" to kv("name" to s("my-app-76dbcf4d9d-w587d"), "namespace" to s("my-namespace"),
            "creationTimestamp" to s("2026-10-02T11:00:00Z"), "deletionTimestamp" to s("2026-10-02T12:01:00Z"),
            "finalizers" to arr(s("example.com/hold")),
            "ownerReferences" to arr(kv("kind" to s("ReplicaSet"), "name" to s("my-app-76dbcf4d9d"),
                "controller" to """{"boolValue":true}"""))),
        "status" to kv("phase" to s("Running")),
    )
    private val json = """{"resourceLogs":[{"resource":{"attributes":[]},"scopeLogs":[{"logRecords":[
        {"body":$pending},{"body":${kv("type" to s("MODIFIED"), "object" to terminating)}},
        {"body":${kv("kind" to s("Service"), "metadata" to kv("name" to s("svc")))}}]}]}]}"""

    @Test
    fun `reads phase, timestamps, finalizers, the pending reason and derives the workload`() {
        val pods = OtlpJsonPods.parse(jacksonObjectMapper().readTree(json), Instant.EPOCH)
        assertEquals(2, pods.size, "the Service is skipped")
        val p = pods[0]
        assertEquals("stuck-pending", p.workload, "a bare pod is its own workload")
        assertEquals("Pending", p.phase)
        assertEquals(Instant.parse("2026-10-02T12:00:00Z"), p.createdAt)
        assertNull(p.deletionAt)
        assertEquals("Unschedulable: 0/3 nodes are available: 3 node(s) didn't match Pod's node affinity/selector.", p.reason)
        val t = pods[1]
        assertEquals("my-app", t.workload)
        assertEquals(Instant.parse("2026-10-02T12:01:00Z"), t.deletionAt)
        assertEquals(listOf("example.com/hold"), t.finalizers)
        assertEquals("Running", t.phase)
    }
}
