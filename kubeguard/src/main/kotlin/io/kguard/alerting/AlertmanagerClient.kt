package io.kguard.alerting

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Alertmanager API v2. The base URL is `quarkus.rest-client.alertmanager.url`. */
@RegisterRestClient(configKey = "alertmanager")
@Path("/api/v2/alerts")
interface AlertmanagerClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun postAlerts(alerts: List<AlertmanagerAlert>)
}
