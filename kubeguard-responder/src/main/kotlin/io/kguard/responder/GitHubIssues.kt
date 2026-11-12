package io.kguard.responder

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Opens one GitHub issue per problem (alertname + service + dependency), or comments on the open one, found by a
 * hidden marker. Labels follow the repo's routing: `kagent-diagnosis` plus `fix issue agent` for code/config changes
 * or `fix issue human` otherwise.
 */
class GitHubIssues(private val repository: String, private val token: String?) : IssueTracker {
    private val log = Logger.getLogger(GitHubIssues::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val mapper = jacksonObjectMapper()

    override fun report(run: IncidentRun, alert: AmAlert, reason: String): String {
        val marker = "<!-- kubeguard-incident: ${alert.alertName}/${alert.service}/${alert.dependency} -->"
        val body = IssueText.body(run, alert, reason, marker)
        if (token.isNullOrBlank()) {
            log.infof("GitHub disabled, would open an issue:\n%s", body)
            return "(not opened: no GitHub token)"
        }
        val existing = openIssueWith(marker)
        if (existing != null) {
            val number = existing.first
            call("POST", "/repos/$repository/issues/$number/comments", mapOf("body" to body))
            return existing.second
        }
        val created = call("POST", "/repos/$repository/issues", mapOf(
            "title" to "[kubeguard] ${alert.annotations["summary"] ?: alert.alertName}",
            "body" to body,
            "labels" to IssueText.labels(run.diagnosis),
        ))
        return mapper.readTree(created).path("html_url").asText()
    }

    private fun openIssueWith(marker: String): Pair<Int, String>? {
        val list = mapper.readTree(call("GET", "/repos/$repository/issues?state=open&labels=kagent-diagnosis&per_page=50", null))
        return list.firstOrNull { it.path("body").asText().contains(marker) }
            ?.let { it.path("number").asInt() to it.path("html_url").asText() }
    }

    private fun call(method: String, path: String, body: Any?): String {
        val request = HttpRequest.newBuilder(URI.create("https://api.github.com$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) }
                ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "GitHub $method $path: HTTP ${response.statusCode()}" }
        return response.body()
    }
}

/** Issue text and labels, separate so they can be tested without GitHub. */
object IssueText {
    fun labels(diagnosis: Diagnosis?): List<String> = listOf(
        "kagent-diagnosis",
        if (diagnosis?.fixBy?.lowercase() in setOf("code", "config")) "fix issue agent" else "fix issue human",
    )

    fun body(run: IncidentRun, alert: AmAlert, reason: String, marker: String): String = buildString {
        appendLine("KubeGuard detected **${alert.alertName}** for `${alert.service}` → `${alert.dependency}`.")
        appendLine(if (run.phase == Phase.FIXED) "Fixed in the cluster, follow-up needed: $reason." else "The responder could not fix it in the cluster: $reason.")
        appendLine()
        appendLine("## Evidence (KubeGuard, deterministic)")
        appendLine("- ${alert.annotations["description"].orEmpty()}")
        alert.annotations["by_instance"]?.let { appendLine("- per pod: $it") }
        appendLine("- incident: `${alert.incidentId}`, firing since ${alert.startsAt.orEmpty()}")
        appendLine()
        appendLine("## Diagnosis (kagent)")
        val d = run.diagnosis
        if (d == null) {
            appendLine("No structured diagnosis.")
        } else {
            appendLine("- summary: ${d.summary}")
            appendLine("- root cause: ${d.rootCause}")
            appendLine("- confidence: ${d.confidence}, proposed action: `${d.action}` ${d.target.orEmpty()}, fix by: ${d.fixBy}")
            d.evidence.forEach { appendLine("- evidence: $it") }
        }
        run.agentAnswer?.let {
            appendLine()
            appendLine("<details><summary>Full agent answer</summary>")
            appendLine()
            appendLine("```text")
            appendLine(it.replace("```", "'''"))
            appendLine("```")
            appendLine("</details>")
        }
        appendLine()
        appendLine("## Responder timeline")
        run.timeline.forEach { appendLine("- $it") }
        appendLine()
        append(marker)
    }
}
