package io.kguard.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The span shapes are the ones the Quarkus backend produces with quarkus.datasource.jdbc.telemetry=true. */
class SpanClassifierTest {
    private val classifier = SpanClassifier(
        connectionErrorPattern = Regex("(?i)(connect|i/o error|timed out|timeout|socket|unknown ?host)"),
        jdbcExceptionPattern = Regex("(?i)(jdbc|java\\.sql\\.|hibernate|postgresql|agroal)"),
    )
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val service = "my-app"
    private val db = "my-app-postgresql"

    private fun jdbc(trace: String, error: Boolean = false, type: String? = null, message: String? = null) = ParsedSpan(
        trace, service, "pod-a", "INSERT app.echo_message", ParsedSpan.SPAN_KIND_CLIENT, 3_000_000, error,
        mapOf("db.system" to "postgresql", "server.address" to db), type, message,
    )

    private fun server(trace: String, error: Boolean, type: String? = null, message: String? = null) = ParsedSpan(
        trace, service, "pod-a", "POST /api/echo", ParsedSpan.SPAN_KIND_SERVER, 3_000_000_000, error,
        mapOf("server.address" to "localhost", "error.type" to "500"), type, message,
    )

    @Test
    fun `database statement I-O error is a connectivity failure, a SQL error is not`() {
        val samples = classifier.classify(listOf(
            jdbc("t1", error = true, type = "org.postgresql.util.PSQLException",
                message = "An I/O error occurred while sending to the backend."),
            jdbc("t2", error = true, type = "org.postgresql.util.PSQLException",
                message = "ERROR: duplicate key value violates unique constraint"),
            jdbc("t3"),
        ), now)
        assertEquals(listOf(true, false, false), samples.map { it.connectivityFailure })
        assertEquals(listOf(true, true, false), samples.map { it.error })
        assertTrue(samples.all { it.dependency == db })
    }

    @Test
    fun `failed connection acquisition is attributed to the service's database`() {
        classifier.classify(listOf(jdbc("t1"), server("t1", error = false)), now)

        val samples = classifier.classify(listOf(
            server("t2", error = true, type = "org.hibernate.exception.JDBCConnectionException",
                message = "Unable to acquire JDBC Connection [Connection attempt failed.]"),
        ), now)

        val sample = samples.single { !it.inbound }
        assertEquals(db, sample.dependency)
        assertTrue(sample.connectivityFailure)
        assertEquals("org.hibernate.exception.JDBCConnectionException", sample.errorType)
        assertEquals("pod-a", sample.instance)
    }

    @Test
    fun `server spans are not counted twice or without a known database`() {
        val unknown = classifier.classify(listOf(
            server("t0", error = true, type = "org.hibernate.exception.JDBCConnectionException", message = "connect failed"),
        ), now)
        assertTrue(unknown.none { !it.inbound }, "no database seen yet for this service")

        val samples = classifier.classify(listOf(
            // statement failed: the JDBC span already counts, the server span must not count again
            jdbc("t1", error = true, type = "org.postgresql.util.PSQLException", message = "An I/O error occurred"),
            server("t1", error = true, type = "org.hibernate.exception.JDBCConnectionException", message = "I/O error"),
            // not a JDBC exception
            server("t2", error = true, type = "java.net.http.HttpConnectTimeoutException", message = "connect timed out"),
            // JDBC, but not a connection problem
            server("t3", error = true, type = "org.hibernate.exception.ConstraintViolationException", message = "duplicate key"),
        ), now).filterNot { it.inbound }
        assertEquals(1, samples.size)
        assertFalse(samples.single().dependency.isEmpty())
    }

    @Test
    fun `http client call without response is a connectivity failure, with 503 it is not`() {
        fun http(status: String?) = ParsedSpan(
            "t", "mock-backend", null, "GET", ParsedSpan.SPAN_KIND_CLIENT, 1_000_000, status == null || status == "503",
            listOfNotNull("server.address" to "pricing", status?.let { "http.response.status_code" to it }).toMap(),
        )
        val samples = classifier.classify(listOf(http(null), http("503"), http("200")), now)
        assertEquals(listOf(true, false, false), samples.map { it.connectivityFailure })
        assertEquals(listOf(true, true, false), samples.map { it.error })
    }

    @Test
    fun `server span is one inbound request per endpoint`() {
        fun request(status: String, millis: Long, route: String? = "/api/quote") = ParsedSpan(
            "t", "mock-backend", "mock-pod", "GET /api/quote", ParsedSpan.SPAN_KIND_SERVER, millis * 1_000_000, status == "504",
            listOfNotNull("http.request.method" to "GET", route?.let { "http.route" to it },
                "http.response.status_code" to status).toMap(),
        )
        val samples = classifier.classify(listOf(request("200", 420), request("504", 3000), request("200", 5, null)), now)

        assertTrue(samples.all { it.inbound && !it.connectivityFailure })
        assertEquals(listOf("GET /api/quote", "GET /api/quote", "GET /api/quote"), samples.map { it.dependency })
        assertEquals(listOf(false, true, false), samples.map { it.error })
        assertEquals(420, samples.first().duration.toMillis())
    }

    @Test
    fun `client spans are never inbound`() {
        val samples = classifier.classify(listOf(jdbc("t1")), now)
        assertFalse(samples.single().inbound)
    }
}
