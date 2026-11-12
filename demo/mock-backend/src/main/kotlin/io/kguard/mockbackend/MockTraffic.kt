package io.kguard.mockbackend

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Mock OpenTelemetry input stream for the KubeGuard demo. It stands in for a real backend: on a fixed schedule it
 * simulates two incoming requests and, for each, makes a *real* HTTP call to a downstream stub. Every call is
 * recorded as a SERVER span with a CLIENT child span carrying the semantic-convention attributes KubeGuard reads
 * (`server.address`, `http.response.status_code`, `error.type`, span status).
 *
 * Because the calls are real, Chaos Mesh faults on the stubs (network delay, partition) show up in the spans.
 *
 * `GET /api/quote` also reads a small file from a local cache directory (`mock.cache-dir`, an emptyDir in the
 * cluster) before calling pricing. A Chaos Mesh IOChaos delay on that volume makes the endpoint itself slow while
 * pricing stays fast: the service's own p95 rises without any slow dependency.
 */
@ApplicationScoped
class MockTraffic(
    private val tracer: Tracer,
    @ConfigProperty(name = "mock.interval") private val interval: Duration,
    @ConfigProperty(name = "mock.pricing-url") private val pricingUrl: URI,
    @ConfigProperty(name = "mock.inventory-url") private val inventoryUrl: URI,
    @ConfigProperty(name = "mock.connect-timeout") connectTimeout: Duration,
    @ConfigProperty(name = "mock.request-timeout") private val requestTimeout: Duration,
    @ConfigProperty(name = "mock.cache-dir") cacheDir: Optional<String>,
) {
    private class Stats {
        val ok = AtomicLong()
        val failed = AtomicLong()
        val totalMillis = AtomicLong()
    }

    private val log = Logger.getLogger(MockTraffic::class.java)
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(connectTimeout)
        .build()
    private val stats = ConcurrentHashMap<String, Stats>()
    private val quoteCache: Path? = cacheDir.map { Path.of(it, "quote.json") }.orElse(null)

    // The Quarkus simple scheduler ticks at most once per second, so the sub-second request rate uses its own
    // timer. Calls run on a separate pool so that slow calls never delay the next tick.
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val workers = Executors.newFixedThreadPool(32)

    fun onStart(@Observes event: StartupEvent) {
        quoteCache?.let {
            Files.createDirectories(it.parent)
            Files.writeString(it, """{"price": 42}""")
        }
        timer.scheduleAtFixedRate({
            workers.execute { handle("GET", "/api/quote", pricingUrl, quoteCache) }
            workers.execute { handle("POST", "/api/order", inventoryUrl) }
        }, 5_000, interval.toMillis(), TimeUnit.MILLISECONDS)
        log.infof("mock traffic started interval=%s pricing=%s inventory=%s quote_cache=%s",
            interval, pricingUrl, inventoryUrl, quoteCache ?: "-")
    }

    fun onStop(@Observes event: ShutdownEvent) {
        timer.shutdownNow()
        workers.shutdownNow()
    }

    /** One summary line per dependency every 10s, so `kubectl logs` shows what the chaos does. */
    @Scheduled(every = "10s", delayed = "10s")
    fun logSummary() {
        stats.forEach { (dependency, s) ->
            val ok = s.ok.getAndSet(0)
            val failed = s.failed.getAndSet(0)
            val total = s.totalMillis.getAndSet(0)
            val avg = if (ok + failed > 0) total / (ok + failed) else 0
            log.infof("traffic dependency=%s ok=%d failed=%d avg_ms=%d", dependency, ok, failed, avg)
        }
    }

    private fun handle(method: String, route: String, target: URI, cache: Path? = null) {
        val server = tracer.spanBuilder("$method $route")
            .setNoParent()
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("http.request.method", method)
            .setAttribute("http.route", route)
            .setAttribute("url.path", route)
            .startSpan()
        try {
            server.makeCurrent().use {
                cache?.let(::readCache)
                val status = callDownstream(target)
                server.setAttribute("http.response.status_code", status.toLong())
                if (status >= 500) server.setStatus(StatusCode.ERROR)
            }
        } finally {
            server.end()
        }
    }

    /** Local work inside the request (INTERNAL span): reading the cached quote. */
    private fun readCache(file: Path) {
        val span = tracer.spanBuilder("read quote cache").setSpanKind(SpanKind.INTERNAL)
            .setAttribute("file.path", file.toString())
            .startSpan()
        try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            fail(span, e)
        } finally {
            span.end()
        }
    }

    /** Returns the status the simulated endpoint answers with: 200, or 502/504 if the dependency failed. */
    private fun callDownstream(target: URI): Int {
        val client = tracer.spanBuilder("GET")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("http.request.method", "GET")
            .setAttribute("server.address", target.host)
            .setAttribute("server.port", target.port.toLong())
            .setAttribute("url.full", target.toString())
            .startSpan()
        val stats = stats.computeIfAbsent(target.host) { Stats() }
        val started = System.nanoTime()
        try {
            val response = http.send(
                HttpRequest.newBuilder(target).timeout(requestTimeout).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            client.setAttribute("http.response.status_code", response.statusCode().toLong())
            if (response.statusCode() >= 500) {
                client.setStatus(StatusCode.ERROR)
                client.setAttribute("error.type", response.statusCode().toString())
                stats.failed.incrementAndGet()
                return 502
            }
            stats.ok.incrementAndGet()
            return 200
        } catch (e: Exception) {
            fail(client, e)
            stats.failed.incrementAndGet()
            return 504
        } finally {
            stats.totalMillis.addAndGet(Duration.ofNanos(System.nanoTime() - started).toMillis())
            client.end()
        }
    }

    private fun fail(span: Span, e: Exception) {
        span.recordException(e)
        span.setAttribute("error.type", e.javaClass.name)
        span.setStatus(StatusCode.ERROR, e.message ?: e.javaClass.simpleName)
    }
}
