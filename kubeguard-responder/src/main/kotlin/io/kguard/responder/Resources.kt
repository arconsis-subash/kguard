package io.kguard.responder

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Alertmanager webhook receiver (`webhook_configs.url`). Returns at once; the work runs in the background. */
@Path("/alertmanager")
class AlertmanagerResource(private val responder: Responder) {
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun receive(payload: AlertmanagerWebhook) = responder.onWebhook(payload)
}

/** Every incident the responder handled, with its diagnosis, decision and timeline. */
@Path("/api/incidents")
class IncidentRunResource(private val responder: Responder) {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun list(): List<IncidentRun> = responder.runs()

    /**
     * A human's answer to a proposal in approve mode. The id is in the body, not the path: service-latency ids contain
     * the endpoint (`GET /api/quote`).
     */
    @POST
    @Path("/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    fun approve(request: ApprovalRequest): Response = answer(responder.approve(request.incidentId, request.by))

    @POST
    @Path("/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    fun reject(request: ApprovalRequest): Response = answer(responder.reject(request.incidentId, request.by))

    /** A human's judgement of a finished run for the pilot report: `{"incidentId": ..., "verdict": "right|wrong|unsure"}`. */
    @POST
    @Path("/verdict")
    @Consumes(MediaType.APPLICATION_JSON)
    fun verdict(request: VerdictRequest): Response {
        val verdict = Verdict.entries.firstOrNull { it.name.equals(request.verdict.trim(), ignoreCase = true) }
            ?: return Response.status(Response.Status.BAD_REQUEST).entity("verdict must be right, wrong or unsure").build()
        return answer(responder.judge(request.incidentId, verdict, request.by))
    }

    private fun answer(result: DecisionResult): Response = when (result) {
        DecisionResult.DONE -> Response.noContent().build()
        DecisionResult.NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build()
        DecisionResult.NOT_WAITING -> Response.status(Response.Status.CONFLICT).build()
    }
}

data class ApprovalRequest(val incidentId: String = "", val by: String = "unknown")

data class VerdictRequest(val incidentId: String = "", val verdict: String = "", val by: String = "unknown")

/** The pilot report: per alert and action, how often, what happened, how often it was judged right. */
@Path("/api/report")
class PilotReportResource(private val responder: Responder) {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun report(): PilotReport.Report = PilotReport.of(responder.runs())

    @GET
    @Path("/csv")
    @Produces("text/csv")
    fun csv(): Response = Response.ok(PilotReport.csv(responder.runs()))
        .header("Content-Disposition", "attachment; filename=\"kguard-pilot-report.csv\"").build()
}
