package io.kguard.ingest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OtlpJsonHeapTest {
    /**
     * Recorded from otel-collector-contrib 0.161.0 (prometheus receiver scraping the backend's /q/metrics, otlp_http
     * with encoding json), trimmed to the heap pools and Metaspace. SerialGC: Eden, Survivor, Tenured Gen.
     */
    private val recorded = """
        {
         "resourceMetrics": [
          {
           "resource": {
            "attributes": [
             {
              "key": "service.name",
              "value": {
               "stringValue": "my-app"
              }
             },
             {
              "key": "service.instance.id",
              "value": {
               "stringValue": "host.docker.internal:18081"
              }
             }
            ]
           },
           "scopeMetrics": [
            {
             "metrics": [
              {
               "name": "jvm_memory_used_bytes",
               "gauge": {
                "dataPoints": [
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "heap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Eden Space"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": 53057904
                 },
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "heap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Tenured Gen"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": 17540016
                 },
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "heap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Survivor Space"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": 0
                 },
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "nonheap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Metaspace"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": 65245760
                 }
                ]
               }
              },
              {
               "name": "jvm_memory_max_bytes",
               "gauge": {
                "dataPoints": [
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "heap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Eden Space"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": 71630848
                 },
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "heap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Tenured Gen"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": 178978816
                 },
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "heap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Survivor Space"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": 8912896
                 },
                 {
                  "attributes": [
                   {
                    "key": "area",
                    "value": {
                     "stringValue": "nonheap"
                    }
                   },
                   {
                    "key": "id",
                    "value": {
                     "stringValue": "Metaspace"
                    }
                   },
                   {
                    "key": "pod",
                    "value": {
                     "stringValue": "my-app-6dd56f6c7c-aaaaa"
                    }
                   }
                  ],
                  "asDouble": -1
                 }
                ]
               }
              }
             ]
            }
           ]
          }
         ]
        }
    """.trimIndent()

    @Test
    fun `sums the heap pools of each pod, leaves out non-heap, derives the workload`() {
        val samples = OtlpJsonHeap.parse(jacksonObjectMapper().readTree(recorded), Instant.EPOCH)
        val sample = samples.single()
        assertEquals("my-app-6dd56f6c7c-aaaaa", sample.pod)
        assertEquals("my-app", sample.workload)
        assertEquals(53057904.0 + 17540016.0 + 0.0, sample.usedBytes)
        assertEquals(71630848.0 + 178978816.0 + 8912896.0, sample.maxBytes)
    }

    @Test
    fun `pools without a maximum (G1 Eden) add nothing to max, points without a pod are skipped`() {
        fun point(id: String, value: Double, pod: String? = "backend-6dd56f6c7c-aaaaa") =
            """{"attributes":[{"key":"area","value":{"stringValue":"heap"}},{"key":"id","value":{"stringValue":"$id"}}""" +
                (pod?.let { """,{"key":"pod","value":{"stringValue":"$it"}}""" } ?: "") + """],"asDouble":$value}"""
        val json = """{"resourceMetrics":[{"scopeMetrics":[{"metrics":[
            {"name":"jvm_memory_used","gauge":{"dataPoints":[${point("G1 Eden Space", 10.0)},${point("G1 Old Gen", 90.0)},${point("G1 Old Gen", 5.0, pod = null)}]}},
            {"name":"jvm_memory_max","gauge":{"dataPoints":[${point("G1 Eden Space", -1.0)},${point("G1 Old Gen", 256.0)}]}}]}]}]}"""
        val sample = OtlpJsonHeap.parse(jacksonObjectMapper().readTree(json), Instant.EPOCH).single()
        assertEquals(100.0, sample.usedBytes)
        assertEquals(256.0, sample.maxBytes)
        assertEquals("backend", sample.workload)
    }

    @Test
    fun `container state metrics are not heap`() {
        val json = """{"resourceMetrics":[{"scopeMetrics":[{"metrics":[{"name":"k8s.container.ready","gauge":{"dataPoints":[{"asInt":"1"}]}}]}]}]}"""
        assertTrue(OtlpJsonHeap.parse(jacksonObjectMapper().readTree(json), Instant.EPOCH).isEmpty())
    }
}
