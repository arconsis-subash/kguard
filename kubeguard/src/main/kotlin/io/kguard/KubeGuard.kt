package io.kguard

import io.kguard.alerting.AlertPublisher
import io.kguard.alerting.AlertSink
import io.kguard.alerting.AlertmanagerClient
import io.kguard.engine.DependencyConnectivityRule
import io.kguard.engine.DetectionRule
import io.kguard.engine.DownstreamLatencyRule
import io.kguard.engine.IncidentEngine
import io.kguard.engine.IncidentState
import io.kguard.engine.IncidentView
import io.kguard.engine.RuleSettings
import io.kguard.engine.PodCrashLoopRule
import io.kguard.engine.PodUnavailableRule
import io.kguard.engine.MemoryExhaustionRule
import io.kguard.ingest.OtlpJsonHeap
import io.kguard.engine.PodHealthEngine
import io.kguard.engine.PodStuckRule
import io.kguard.engine.ServiceLatencyRule
import io.kguard.engine.Transition
import io.kguard.ingest.OtlpJsonMetrics
import io.kguard.ingest.OtlpJsonPods
import io.kguard.ingest.OtlpJsonSpans
import io.kguard.ingest.SpanClassifier
import com.fasterxml.jackson.databind.JsonNode
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Wires telemetry ingest, the deterministic [IncidentEngine] and the [AlertPublisher] together. */
@ApplicationScoped
class KubeGuard(
    private val config: KubeGuardConfig,
    @RestClient private val alertmanager: AlertmanagerClient,
    private val metrics: MeterRegistry,
) {
    private val log = Logger.getLogger(KubeGuard::class.java)
    private val clock: Clock = Clock.systemUTC()
    private val rules: List<DetectionRule> = buildRules()
    private val engine = IncidentEngine(rules, clock)
    // Rules over the container state (restarts, readiness); one engine, so each report is kept once.
    private val podEngine = listOfNotNull(
        config.rules().podCrashloop().takeIf { it.enabled() }
            ?.let { PodCrashLoopRule(settings(it), it.minRestarts(), it.staleAfter()) to it.staleAfter() },
        config.rules().podUnavailable().takeIf { it.enabled() }
            ?.let { PodUnavailableRule(settings(it), it.staleAfter()) to it.staleAfter() },
    ).takeIf { it.isNotEmpty() }?.let { rules ->
        PodHealthEngine(rules.map { it.first }, clock, rules.maxOf { it.second })
    }
    private val memoryEngine = config.rules().memoryExhaustion().takeIf { it.enabled() }?.let {
        PodHealthEngine(
            listOf(MemoryExhaustionRule(settings(it), it.bucket(), it.minBuckets(), it.minFraction(), it.predictWithin(),
                it.staleAfter())),
            clock, it.staleAfter(),
        )
    }
    private val stuckRules = listOf(
        config.rules().podPending() to PodStuckRule.Stuck.PENDING,
        config.rules().podTerminating() to PodStuckRule.Stuck.TERMINATING,
    ).filter { it.first.enabled() }
    private val stuckEngine = stuckRules.takeIf { it.isNotEmpty() }?.let { rules ->
        PodHealthEngine(
            rules.map { (rule, stuck) -> PodStuckRule(settings(rule), stuck, rule.stuckAfter(), rule.staleAfter()) },
            clock,
            rules.maxOf { it.first.staleAfter() },
        )
    }
    private val sink = AlertSink { alerts ->
        if (config.alertmanager().enabled()) alertmanager.postAlerts(alerts)
        else log.infof("alertmanager disabled, would send %d alert(s)", alerts.size)
        metrics.counter("kubeguard.alertmanager.alerts.sent").increment(alerts.size.toDouble())
    }
    private val publisher = AlertPublisher(
        sink = sink,
        resendInterval = config.alertmanager().resendInterval(),
        alertTtl = config.alertmanager().alertTtl(),
        staticLabels = config.alertmanager().staticLabels(),
        generatorUrl = config.alertmanager().generatorUrl().orElse(null),
    )
    private val classifier = SpanClassifier(
        connectionErrorPattern = Regex(config.ingest().connectionErrorPattern()),
        jdbcExceptionPattern = Regex(config.ingest().jdbcExceptionPattern()),
    )
    private val lastEvaluationAt = AtomicLong(0)

    init {
        metrics.gauge("kubeguard.incidents.firing", this) { it.publisher.activeAlerts().toDouble() }
    }

    fun onStart(@Observes event: StartupEvent) {
        rules.forEach {
            log.infof("rule enabled name=%s alertname=%s window=%s min_samples=%d firing_duration=%s recovery_duration=%s",
                it.name, it.alertName, it.settings.window, it.settings.minSamples, it.settings.firingDuration,
                it.settings.recoveryDuration)
        }
    }

    fun ingest(request: JsonNode) {
        val allowed = config.services().orElse(emptyList())
        val samples = classifier.classify(OtlpJsonSpans.parse(request), clock.instant())
        val accepted = samples.filter { allowed.isEmpty() || it.service in allowed }
        accepted.forEach(engine::record)
        metrics.counter("kubeguard.spans.received", "accepted", "true").increment(accepted.size.toDouble())
        metrics.counter("kubeguard.spans.received", "accepted", "false")
            .increment((samples.size - accepted.size).toDouble())
    }

    /**
     * OTLP metrics: pod and container state from the Collector's k8s_cluster receiver, and the JVM heap of each pod
     * from its prometheus receiver. Each parser keeps only its own metrics.
     */
    fun ingestMetrics(request: JsonNode) {
        val states = OtlpJsonMetrics.parse(request, clock.instant())
        podEngine?.record(states)
        metrics.counter("kubeguard.container.states.received").increment(states.size.toDouble())
        val heaps = OtlpJsonHeap.parse(request, clock.instant())
        memoryEngine?.record(heaps)
        metrics.counter("kubeguard.heap.samples.received").increment(heaps.size.toDouble())
    }

    /** Pod objects from the Collector's k8sobjects receiver (OTLP logs), for the stuck Pending/Terminating rules. */
    fun ingestPods(request: JsonNode) {
        val pods = OtlpJsonPods.parse(request, clock.instant())
        stuckEngine?.record(pods)
        metrics.counter("kubeguard.pods.received").increment(pods.size.toDouble())
    }

    @Scheduled(every = "\${kubeguard.evaluation-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun evaluate() {
        val transitions = engine.evaluate() + (podEngine?.evaluate() ?: emptyList()) +
            (memoryEngine?.evaluate() ?: emptyList()) + (stuckEngine?.evaluate() ?: emptyList())
        transitions.forEach {
            logTransition(it)
            metrics.counter("kubeguard.transitions", "rule", it.key.rule, "to", it.to.name).increment()
            publisher.apply(it)
        }
        publisher.flush(clock.instant())
        lastEvaluationAt.set(clock.millis())
    }

    fun incidents(): List<IncidentView> = engine.snapshot() + (podEngine?.snapshot() ?: emptyList()) +
        (memoryEngine?.snapshot() ?: emptyList()) + (stuckEngine?.snapshot() ?: emptyList())

    fun lastEvaluation(): Instant? = lastEvaluationAt.get().takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    fun alertmanagerStatus(): Pair<Boolean?, Instant?> = publisher.lastDeliveryOk to publisher.lastDeliveryAt

    private fun logTransition(t: Transition) {
        val level = if (t.to == IncidentState.FIRING) Logger.Level.WARN else Logger.Level.INFO
        log.logf(level, "incident transition rule=%s service=%s dependency=%s from=%s to=%s incident_id=%s reason=\"%s\"",
            t.key.rule, t.key.service, t.key.dependency, t.from, t.to, t.incidentId ?: "-", t.evaluation.explanation)
    }

    private fun buildRules(): List<DetectionRule> {
        val latency = config.rules().downstreamLatency()
        val connectivity = config.rules().dependencyConnectivity()
        val serviceLatency = config.rules().serviceLatency()
        return listOfNotNull(
            DownstreamLatencyRule(settings(latency), latency.p95Threshold()).takeIf { latency.enabled() },
            DependencyConnectivityRule(settings(connectivity), connectivity.failureRatioThreshold())
                .takeIf { connectivity.enabled() },
            ServiceLatencyRule(settings(serviceLatency), serviceLatency.p95Threshold()).takeIf { serviceLatency.enabled() },
        )
    }

    private fun settings(rule: KubeGuardConfig.Rule) = RuleSettings(
        window = rule.window(),
        minSamples = rule.minSamples(),
        firingDuration = rule.firingDuration(),
        recoveryDuration = rule.recoveryDuration(),
        severity = rule.severity(),
    )
}
