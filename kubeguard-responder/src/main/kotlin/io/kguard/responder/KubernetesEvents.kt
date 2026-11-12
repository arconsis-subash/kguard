package io.kguard.responder

import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import java.time.Clock
import java.time.format.DateTimeFormatter

/**
 * Writes each decision as a Kubernetes Event on the Deployment it concerns ([IncidentRun.deployment], else the alert's
 * service, which is the Deployment's name). The API server keeps Events for an hour by default; an event exporter or
 * the log pipeline (the responder also logs every line) keeps them longer.
 */
class KubernetesEvents(
    private val client: KubernetesClient,
    private val namespace: String,
    private val clock: Clock,
) : AuditLog {
    override fun record(run: IncidentRun, reason: AuditReason, message: String) {
        val now = DateTimeFormatter.ISO_INSTANT.format(clock.instant())
        val event = EventBuilder()
            .withNewMetadata().withGenerateName("kguard-").withNamespace(namespace)
                .addToLabels("app.kubernetes.io/name", "kubeguard-responder").endMetadata()
            .withInvolvedObject(ObjectReferenceBuilder().withApiVersion("apps/v1").withKind("Deployment")
                .withNamespace(namespace).withName(run.deployment ?: run.service).build())
            .withReason(reason.name)
            .withMessage("[${run.incidentId}] $message".take(1024))
            .withType(if (reason.warning) "Warning" else "Normal")
            .withNewSource().withComponent("kubeguard-responder").endSource()
            .withReportingComponent("kubeguard-responder")
            .withFirstTimestamp(now).withLastTimestamp(now).withCount(1)
            .build()
        client.v1().events().inNamespace(namespace).resource(event).create()
    }
}
