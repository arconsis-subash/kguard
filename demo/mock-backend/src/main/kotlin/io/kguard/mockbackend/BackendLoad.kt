package io.kguard.mockbackend

import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional steady load on the real backend (`mock.backend-load.url`, e.g. its `POST /api/echo`), so that the
 * backend's own telemetry (SERVER and JDBC spans) has enough samples for KubeGuard. These calls are deliberately
 * not traced here: KubeGuard evaluates the backend's spans, not this load generator's.
 *
 * Every request uses a new connection (`Connection: close`). With keep-alive all requests would stick to one backend
 * pod (the Service balances connections, not requests), and a problem on the other pod would stay invisible.
 * "Connection" is a restricted header of java.net.http: the JVM needs
 * `-Djdk.httpclient.allowRestrictedHeaders=connection` (set in the Helm chart), otherwise the start fails.
 */
@ApplicationScoped
class BackendLoad(
    @ConfigProperty(name = "mock.backend-load.url") private val url: Optional<URI>,
    @ConfigProperty(name = "mock.backend-load.interval") private val interval: Duration,
) {
    private val log = Logger.getLogger(BackendLoad::class.java)

    // HTTP/1.1 only: the default HTTP/2 client would send an h2c upgrade, which the Quarkus backend accepts and which
    // does not combine with "Connection: close".
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val results = ConcurrentHashMap<String, AtomicLong>()

    fun onStart(@Observes event: StartupEvent) {
        val target = url.orElse(null) ?: return
        val request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Connection", "close")
            .POST(HttpRequest.BodyPublishers.ofString("""{"message":"kubeguard-load"}"""))
            .build()
        timer.scheduleAtFixedRate({
            // An exception escaping a scheduled task would silently cancel all further runs.
            try {
                http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { response, error ->
                    val outcome = response?.statusCode()?.toString() ?: error?.javaClass?.simpleName ?: "unknown"
                    results.computeIfAbsent(outcome) { AtomicLong() }.incrementAndGet()
                }
            } catch (e: Exception) {
                log.warnf("backend load request failed to start: %s", e.toString())
            }
        }, 5_000, interval.toMillis(), TimeUnit.MILLISECONDS)
        timer.scheduleAtFixedRate({
            val summary = results.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.getAndSet(0)}" }
            log.infof("backend load target=%s results: %s", target, summary)
        }, 10, 10, TimeUnit.SECONDS)
        log.infof("backend load started target=%s interval=%s", target, interval)
    }

    fun onStop(@Observes event: ShutdownEvent) {
        timer.shutdownNow()
    }
}
