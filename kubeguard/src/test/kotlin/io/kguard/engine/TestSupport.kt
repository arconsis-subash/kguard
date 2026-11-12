package io.kguard.engine

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var now: Instant = Instant.parse("2026-10-01T12:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

val settings = RuleSettings(
    window = Duration.ofSeconds(20),
    minSamples = 10,
    firingDuration = Duration.ofSeconds(30),
    recoveryDuration = Duration.ofSeconds(30),
    severity = "warning",
)

fun ok(at: Instant, millis: Long, dependency: String = "pricing") =
    SpanSample(at, "mock-backend", dependency, Duration.ofMillis(millis), error = false, httpStatus = 200)

fun connectFailure(at: Instant, dependency: String = "inventory") = SpanSample(
    at, "mock-backend", dependency, Duration.ofMillis(1000), error = true, errorType = "java.net.http.HttpConnectTimeoutException",
)

fun request(at: Instant, millis: Long, endpoint: String = "GET /api/quote") = SpanSample(
    at, "mock-backend", endpoint, Duration.ofMillis(millis), error = false, httpStatus = 200, inbound = true,
)
