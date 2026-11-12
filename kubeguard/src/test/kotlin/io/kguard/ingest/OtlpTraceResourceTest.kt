package io.kguard.ingest

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test

@QuarkusTest
class OtlpTraceResourceTest {

    /** OTLP/HTTP JSON as the Collector sends it: enums as numbers, int64 as strings. */
    private val payload = """
        {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"mock-backend"}}]},
          "scopeSpans":[{"scope":{"name":"mock"},"spans":[
            {"traceId":"5b8efff798038103d269b633813fc60c","spanId":"eee19b7ec3c1b174","name":"GET",
             "kind":3,"startTimeUnixNano":"1727784000000000000","endTimeUnixNano":"1727784000120000000",
             "attributes":[{"key":"server.address","value":{"stringValue":"pricing"}},
                           {"key":"http.response.status_code","value":{"intValue":"200"}}],
             "status":{}},
            {"traceId":"5b8efff798038103d269b633813fc60d","spanId":"eee19b7ec3c1b175","name":"GET",
             "kind":3,"startTimeUnixNano":"1727784000000000000","endTimeUnixNano":"1727784001000000000",
             "attributes":[{"key":"server.address","value":{"stringValue":"inventory"}},
                           {"key":"error.type","value":{"stringValue":"java.net.http.HttpConnectTimeoutException"}}],
             "status":{"code":2,"message":"connect timed out"}},
            {"traceId":"5b8efff798038103d269b633813fc60e","spanId":"eee19b7ec3c1b176","name":"GET /api/quote",
             "kind":2,"startTimeUnixNano":"1727784000000000000","endTimeUnixNano":"1727784000130000000"}
          ]}]}]}
    """.trimIndent()

    @Test
    fun `accepts OTLP JSON and tracks one key per rule and dependency`() {
        given().contentType("application/json").body(payload)
            .post("/v1/traces")
            .then().statusCode(200)

        given().get("/api/incidents")
            .then().statusCode(200)
            .body("dependency", hasItems("pricing", "inventory"))
            .body("rule", hasItems("downstream-latency", "dependency-connectivity"))
            .body("state", hasItems("NORMAL"))
    }

    @Test
    fun `health endpoints are up`() {
        given().get("/q/health/ready").then().statusCode(200)
        given().get("/q/health/live").then().statusCode(200)
    }
}
