package io.kguard.api

import io.kguard.KubeGuard
import io.kguard.engine.IncidentView
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

/** Current state of every tracked incident key, including the last evaluation and why it fired. */
@Path("/api/incidents")
class IncidentResource(private val guard: KubeGuard) {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun incidents(): List<IncidentView> = guard.incidents()
}
