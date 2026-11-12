package io.kguard.responder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Asks a kagent agent over A2A (`message/send`, blocking), like the health check job does. */
class KagentA2a(private val url: String, private val timeout: Duration) : DiagnosisAgent {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val mapper = jacksonObjectMapper()

    override fun ask(prompt: String): String {
        val body = mapper.writeValueAsString(mapOf(
            "jsonrpc" to "2.0",
            "id" to "1",
            "method" to "message/send",
            "params" to mapOf("message" to mapOf(
                "kind" to "message",
                "role" to "user",
                "messageId" to UUID.randomUUID().toString(),
                "parts" to listOf(mapOf("kind" to "text", "text" to prompt)),
            )),
        ))
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            // kagent runs in unsecure auth mode and uses this as the user id; keeps these sessions apart.
            .header("X-User-Id", "kubeguard-responder@kagent")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "kagent answered HTTP ${response.statusCode()}" }
        val payload = mapper.readTree(response.body())
        payload.get("error")?.let { error("kagent error ${it.path("code").asText()}: ${it.path("message").asText()}") }
        return answerText(payload.path("result"))
    }

    private fun answerText(task: JsonNode): String {
        val text = task.path("artifacts").flatMap { it.path("parts") }
            .filter { it.path("kind").asText() == "text" }
            .joinToString("\n") { it.path("text").asText() }
            .trim()
        check(text.isNotEmpty()) { "kagent returned no text (task state ${task.path("status").path("state").asText()})" }
        return text
    }
}
