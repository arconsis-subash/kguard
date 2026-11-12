package io.kguard.responder

import java.time.Duration

/** A human's judgement of what the responder decided or proposed, for the pilot report. */
enum class Verdict { RIGHT, WRONG, UNSURE }

/**
 * What a shadow-mode pilot measures, from the responder's runs (`GET /api/report`, `GET /api/report/csv`): how often
 * each alert fired, what the responder did or would have done, how it ended, and how often a human judged the decision
 * right. The runs are in memory, so the report covers the time since the responder started.
 */
object PilotReport {
    data class Row(
        val alert: String,
        val action: String,
        val incidents: Int,
        val shadowed: Int,
        val acted: Int,
        val fixed: Int,
        val escalated: Int,
        val resolvedByItself: Int,
        val withoutDiagnosis: Int,
        val judgedRight: Int,
        val judgedWrong: Int,
        val judgedUnsure: Int,
        /** From the alert reaching the responder to KubeGuard's "resolved", over the fixed runs; null if none. */
        val medianSecondsToFixed: Long?,
    )

    data class Report(val since: String?, val incidents: Int, val rows: List<Row>)

    /** The action the responder took or proposed, or `none` (escalated without a proposal, resolved before acting). */
    fun actionOf(run: IncidentRun): String = run.decision?.substringBefore(' ') ?: "none"

    private fun withoutDiagnosis(run: IncidentRun) = run.diagnosis?.summary?.startsWith("no AI diagnosis") == true

    private fun secondsToFixed(run: IncidentRun): Long? =
        run.finishedAt?.takeIf { run.phase == Phase.FIXED }?.let { Duration.between(run.receivedAt, it).toSeconds() }

    fun of(runs: List<IncidentRun>): Report = Report(
        since = runs.minOfOrNull { it.receivedAt }?.toString(),
        incidents = runs.size,
        rows = runs.groupBy { it.alertName to actionOf(it) }.map { (key, group) ->
            val times = group.mapNotNull(::secondsToFixed).sorted()
            Row(
                alert = key.first,
                action = key.second,
                incidents = group.size,
                shadowed = group.count { it.phase == Phase.SHADOWED },
                acted = group.count { it.actionAt != null },
                fixed = group.count { it.phase == Phase.FIXED },
                escalated = group.count { it.phase == Phase.ESCALATED },
                resolvedByItself = group.count { it.phase == Phase.RESOLVED_BEFORE_ACTION },
                withoutDiagnosis = group.count(::withoutDiagnosis),
                judgedRight = group.count { it.verdict == Verdict.RIGHT },
                judgedWrong = group.count { it.verdict == Verdict.WRONG },
                judgedUnsure = group.count { it.verdict == Verdict.UNSURE },
                medianSecondsToFixed = times.takeIf { it.isNotEmpty() }?.let { it[(it.size - 1) / 2] },
            )
        }.sortedWith(compareByDescending<Row> { it.incidents }.thenBy { it.alert }),
    )

    /** One line per run, for the pilot report in a spreadsheet. */
    fun csv(runs: List<IncidentRun>): String = buildString {
        appendLine("incident_id,received_at,alert,service,dependency,phase,autonomy,decision,without_diagnosis,verdict,verdict_by,seconds_to_fixed,issue")
        runs.forEach { r ->
            appendLine(listOf(r.incidentId, r.receivedAt.toString(), r.alertName, r.service, r.dependency, r.phase.name,
                r.autonomy?.name.orEmpty(), r.decision.orEmpty(), withoutDiagnosis(r).toString(), r.verdict?.name.orEmpty(),
                r.verdictBy.orEmpty(), secondsToFixed(r)?.toString().orEmpty(), r.issueUrl.orEmpty())
                .joinToString(",") { cell -> if (cell.any { it in ",\"\n" }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell })
        }
    }
}
