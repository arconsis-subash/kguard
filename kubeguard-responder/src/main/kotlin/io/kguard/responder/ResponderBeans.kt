package io.kguard.responder

import io.fabric8.kubernetes.client.KubernetesClient
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Produces
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ApplicationScoped
class ResponderBeans {
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()

    // Created at startup, not on the first request: a bad setting (e.g. a typo in responder.autonomy) must stop the
    // pod instead of failing every request behind a green health check, and the Alertmanager sync must run at once.
    @Produces
    @ApplicationScoped
    @Startup
    fun responder(config: ResponderConfig, client: KubernetesClient): Responder {
        val cluster = Fabric8Cluster(client, config.namespace())
        val responder = Responder(
            agent = KagentA2a(config.kagent().url(), config.kagent().timeout()),
            policy = ActionPolicy(cluster, config.policy().allowedDeployments(), config.policy().minConfidence(),
                config.policy().cooldown(), config.policy().maxActions(), config.policy().actionWindow()),
            cluster = cluster,
            issues = GitHubIssues(config.github().repository(),
                config.github().token().filter { config.github().enabled() }.orElse(null)),
            executor = worker,
            clock = Clock.systemUTC(),
            namespace = config.namespace(),
            services = config.services(),
            verifyTimeout = config.verifyTimeout(),
            dryRun = config.dryRun(),
            reportFixes = config.reportFixes(),
            reportOnlyAlerts = config.reportOnlyAlerts(),
            autonomy = Autonomy.parse(config.autonomy().orElse("")),
            approvalTimeout = config.approvalTimeout(),
            defaultActions = DefaultActions.parse(config.defaultActions().orElse("")),
            audit = KubernetesEvents(client, config.namespace(), Clock.systemUTC()),
        )
        timer.scheduleAtFixedRate(responder::checkTimeouts, 10, 10, TimeUnit.SECONDS)
        config.alertmanagerUrl().ifPresent { url ->
            val sync = AlertmanagerSync(url)
            timer.scheduleWithFixedDelay({
                try {
                    responder.onWebhook(AlertmanagerWebhook(status = "firing", alerts = sync.activeAlerts()))
                } catch (e: Exception) {
                    org.jboss.logging.Logger.getLogger(AlertmanagerSync::class.java)
                        .warnf("alertmanager sync failed: %s", e.toString())
                }
            }, 5, 30, TimeUnit.SECONDS)
        }
        return responder
    }

    fun onStop(@Observes event: ShutdownEvent) {
        timer.shutdownNow()
        worker.shutdownNow()
    }
}
