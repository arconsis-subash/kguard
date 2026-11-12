package io.kguard.responder

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Extracts the diagnosis from an agent answer: the last fenced ```json block, also when the model left out the
 * closing fence. Returns null if there is none or it is not valid JSON.
 */
object DiagnosisParser {
    private val mapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val fence = Regex("```(?:json|diagnosis)?[ \\t]*\\n")

    fun parse(answer: String): Diagnosis? {
        val start = fence.findAll(answer).lastOrNull()?.range?.last?.plus(1) ?: return null
        val rest = answer.substring(start)
        val json = rest.substring(0, rest.lastIndexOf('}') + 1).takeIf { it.trimStart().startsWith("{") } ?: return null
        return runCatching { mapper.readValue<Diagnosis>(json) }.getOrNull()
    }
}
