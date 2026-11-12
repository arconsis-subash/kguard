package io.kguard.ingest

import io.kguard.engine.SpanSample
import java.time.Duration
import java.time.Instant

/**
 * Turns parsed spans into call samples per (service, dependency). The rules are explicit:
 *
 * 1. **CLIENT span** = one call. Dependency: `peer.service`, else `server.address`, else `net.peer.name`, else the
 *    span name. Error: span status ERROR or HTTP status >= 500.
 * 2. Connectivity failure of an **HTTP** call: it failed without any response status.
 * 3. Connectivity failure of a **database** call (`db.system` set): it failed and its exception type or message
 *    matches [connectionErrorPattern]. A SQL error such as a constraint violation is not a connectivity failure.
 * 4. **SERVER span** = one request the service answered ([SpanSample.inbound]). Endpoint (the sample's dependency):
 *    `http.request.method` and `http.route`, else the span name. Error: span status ERROR or HTTP status >= 500.
 * 5. **SERVER span** that failed with a JDBC exception ([jdbcExceptionPattern]) that also matches
 *    [connectionErrorPattern], in a trace without any database CLIENT span: the connection could not even be
 *    acquired, which happens before the JDBC instrumentation creates a span. It counts as a connectivity failure of
 *    the database the service was last seen calling. Services with no database calls seen are ignored.
 */
class SpanClassifier(
    private val connectionErrorPattern: Regex,
    private val jdbcExceptionPattern: Regex,
) {
    private val databaseOf = HashMap<String, String>()
    private val tracesWithDbCalls = LinkedHashMap<String, Instant>()

    @Synchronized
    fun classify(spans: List<ParsedSpan>, receivedAt: Instant): List<SpanSample> {
        // Client spans first: they end before their server span, so a trace's database calls are known when its
        // server span is looked at, whether they arrived in this batch or an earlier one.
        val samples = spans.filter { it.isClient }.map { clientSample(it, receivedAt) }.toMutableList()
        for (span in spans.filter { it.isServer }) {
            samples += serverSample(span, receivedAt)
            acquisitionFailure(span, receivedAt)?.let(samples::add)
        }
        forgetOldTraces(receivedAt)
        return samples
    }

    private fun clientSample(span: ParsedSpan, receivedAt: Instant): SpanSample {
        val attrs = span.attributes
        val dependency = attrs["peer.service"] ?: attrs["server.address"] ?: attrs["net.peer.name"] ?: span.name
        val httpStatus = (attrs["http.response.status_code"] ?: attrs["http.status_code"])?.toIntOrNull()
        val error = span.statusError || (httpStatus != null && httpStatus >= 500)
        val isDatabase = attrs.containsKey("db.system") || attrs.containsKey("db.system.name")
        if (isDatabase) {
            databaseOf[span.service] = dependency
            tracesWithDbCalls[span.traceId] = receivedAt
        }
        return SpanSample(
            receivedAt = receivedAt,
            service = span.service,
            dependency = dependency,
            duration = Duration.ofNanos(span.durationNanos),
            error = error,
            httpStatus = httpStatus,
            errorType = attrs["error.type"] ?: span.exceptionType,
            connectivityFailure = if (isDatabase) error && isConnectionError(span) else error && httpStatus == null,
            instance = span.instance,
        )
    }

    private fun serverSample(span: ParsedSpan, receivedAt: Instant): SpanSample {
        val attrs = span.attributes
        val method = attrs["http.request.method"] ?: attrs["http.method"]
        val route = attrs["http.route"]
        val httpStatus = (attrs["http.response.status_code"] ?: attrs["http.status_code"])?.toIntOrNull()
        return SpanSample(
            receivedAt = receivedAt,
            service = span.service,
            dependency = if (method != null && route != null) "$method $route" else span.name,
            duration = Duration.ofNanos(span.durationNanos),
            error = span.statusError || (httpStatus != null && httpStatus >= 500),
            httpStatus = httpStatus,
            errorType = attrs["error.type"] ?: span.exceptionType,
            connectivityFailure = false,
            inbound = true,
            instance = span.instance,
        )
    }

    private fun acquisitionFailure(span: ParsedSpan, receivedAt: Instant): SpanSample? {
        if (!span.statusError || span.traceId in tracesWithDbCalls) return null
        val type = span.exceptionType ?: return null
        if (!jdbcExceptionPattern.containsMatchIn(type) || !isConnectionError(span)) return null
        val database = databaseOf[span.service] ?: return null
        return SpanSample(
            receivedAt = receivedAt,
            service = span.service,
            dependency = database,
            duration = Duration.ofNanos(span.durationNanos),
            error = true,
            errorType = type,
            connectivityFailure = true,
            instance = span.instance,
        )
    }

    private fun isConnectionError(span: ParsedSpan): Boolean =
        listOfNotNull(span.exceptionType, span.exceptionMessage, span.attributes["error.type"])
            .any { connectionErrorPattern.containsMatchIn(it) }

    private fun forgetOldTraces(now: Instant) {
        val cutoff = now.minus(TRACE_MEMORY)
        val iterator = tracesWithDbCalls.values.iterator()
        while (iterator.hasNext() && (iterator.next().isBefore(cutoff) || tracesWithDbCalls.size > MAX_TRACES)) {
            iterator.remove()
        }
    }

    private companion object {
        val TRACE_MEMORY: Duration = Duration.ofMinutes(2)
        const val MAX_TRACES = 50_000
    }
}
