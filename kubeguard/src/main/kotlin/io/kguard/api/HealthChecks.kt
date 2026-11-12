package io.kguard.api

import io.kguard.KubeGuard
import io.kguard.KubeGuardConfig
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.eclipse.microprofile.health.Readiness
import java.time.Duration
import java.time.Instant

/** Live while the evaluation loop keeps running (after a startup grace period). */
@Liveness
@ApplicationScoped
class EvaluationLoopCheck(private val guard: KubeGuard, private val config: KubeGuardConfig) : HealthCheck {
    private val startedAt = Instant.now()

    override fun call(): HealthCheckResponse {
        val maxAge = config.evaluationInterval().multipliedBy(5)
        val last = guard.lastEvaluation()
        val reference = last ?: startedAt
        val alive = Duration.between(reference, Instant.now()) < maxAge.plusSeconds(30)
        return HealthCheckResponse.named("evaluation-loop").status(alive)
            .withData("lastEvaluation", last?.toString() ?: "none yet").build()
    }
}

/**
 * Always ready once started: telemetry must keep flowing in even if Alertmanager is down (alerts are retried).
 * The Alertmanager delivery status is reported for information only.
 */
@Readiness
@ApplicationScoped
class AlertmanagerDeliveryCheck(private val guard: KubeGuard) : HealthCheck {
    override fun call(): HealthCheckResponse {
        val (ok, at) = guard.alertmanagerStatus()
        return HealthCheckResponse.named("alertmanager-delivery").up()
            .withData("lastDeliveryOk", ok?.toString() ?: "nothing sent yet")
            .withData("lastDeliveryAt", at?.toString() ?: "-")
            .build()
    }
}
