package io.kguard.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * State machine of one incident key (see [IncidentState]). Not thread-safe; [IncidentEngine] serialises access.
 *
 * Anti-flapping: the condition must hold for `firingDuration` before firing and be clear for `recoveryDuration`
 * before resolving; any MET evaluation while FIRING restarts the recovery timer. INSUFFICIENT_DATA never fires or
 * resolves an incident: while FIRING it neither starts nor resets the recovery timer, so an incident is not
 * resolved just because traffic stopped.
 */
internal class IncidentTracker(private val key: IncidentKey, private val rule: RuleInfo, now: Instant) {

    var state = IncidentState.NORMAL
        private set
    var lastSampleAt: Instant? = null
    private var stateSince: Instant = now
    private var conditionSince: Instant? = null
    private var clearSince: Instant? = null
    private var firingSince: Instant? = null
    private var incidentId: String? = null
    private var firingEvaluation: Evaluation? = null
    private var lastEvaluation: Evaluation? = null

    fun update(evaluation: Evaluation, now: Instant): Transition? {
        lastEvaluation = evaluation
        val verdict = evaluation.verdict
        return when (state) {
            IncidentState.NORMAL, IncidentState.RECOVERED -> {
                if (verdict != Verdict.MET) return null
                conditionSince = now
                val degraded = moveTo(IncidentState.DEGRADED, now, evaluation)
                // A zero firing duration fires within the same evaluation.
                fireIfDue(now, evaluation) ?: degraded
            }

            IncidentState.DEGRADED -> {
                if (verdict == Verdict.MET) return fireIfDue(now, evaluation)
                conditionSince = null
                moveTo(IncidentState.NORMAL, now, evaluation)
            }

            IncidentState.FIRING -> when (verdict) {
                Verdict.MET -> {
                    clearSince = null
                    null
                }

                Verdict.NOT_MET -> {
                    val clear = clearSince ?: now.also { clearSince = it }
                    if (elapsed(clear, now) < rule.settings.recoveryDuration) return null
                    clearSince = null
                    conditionSince = null
                    moveTo(IncidentState.RECOVERED, now, evaluation)
                }

                Verdict.INSUFFICIENT_DATA -> null
            }
        }
    }

    private fun fireIfDue(now: Instant, evaluation: Evaluation): Transition? {
        if (elapsed(conditionSince!!, now) < rule.settings.firingDuration) return null
        firingSince = now
        firingEvaluation = evaluation
        clearSince = null
        incidentId = "${key.rule}:${key.service}->${key.dependency}:${ID_TIME.format(now)}"
        return moveTo(IncidentState.FIRING, now, evaluation)
    }

    private fun moveTo(next: IncidentState, now: Instant, evaluation: Evaluation): Transition {
        val previous = state
        state = next
        stateSince = now
        val incident = hasIncident()
        return Transition(
            key = key,
            alertName = rule.alertName,
            severity = rule.settings.severity,
            from = previous,
            to = next,
            at = now,
            evaluation = evaluation,
            incidentId = incidentId.takeIf { incident },
            firingSince = firingSince.takeIf { incident },
            firingEvaluation = firingEvaluation.takeIf { incident },
        )
    }

    private fun hasIncident() = state == IncidentState.FIRING || state == IncidentState.RECOVERED

    fun view() = IncidentView(
        rule = key.rule,
        service = key.service,
        dependency = key.dependency,
        state = state,
        stateSince = stateSince,
        conditionSince = conditionSince,
        firingSince = firingSince.takeIf { hasIncident() },
        clearSince = clearSince,
        incidentId = incidentId.takeIf { hasIncident() },
        lastEvaluation = lastEvaluation,
    )

    private fun elapsed(from: Instant, to: Instant): Duration = Duration.between(from, to)

    private companion object {
        val ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
