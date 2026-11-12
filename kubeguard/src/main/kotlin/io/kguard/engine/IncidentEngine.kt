package io.kguard.engine

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Keeps a sliding window of call samples per (service, dependency) and evaluates every rule for every pair it has
 * seen in the rule's direction (outgoing calls, or with [DetectionRule.inbound] the requests a service answered).
 * Deterministic: the same samples and the same clock produce the same transitions.
 */
class IncidentEngine(rules: List<DetectionRule>, private val clock: Clock) {

    private data class Pair(val service: String, val dependency: String, val inbound: Boolean)

    private val rules = rules.toList()
    private val retention: Duration = rules.maxOfOrNull { it.settings.window } ?: Duration.ZERO
    private val samples = LinkedHashMap<Pair, ArrayDeque<SpanSample>>()
    private val trackers = LinkedHashMap<IncidentKey, IncidentTracker>()

    @Synchronized
    fun record(sample: SpanSample) {
        val pair = Pair(sample.service, sample.dependency, sample.inbound)
        samples.getOrPut(pair) { ArrayDeque() }.addLast(sample)
        for (rule in rulesFor(pair)) {
            trackers.getOrPut(IncidentKey(rule.name, pair.service, pair.dependency)) {
                IncidentTracker(IncidentKey(rule.name, pair.service, pair.dependency), rule, clock.instant())
            }.lastSampleAt = sample.receivedAt
        }
    }

    /** Evaluates all rules at the current clock time and returns the state changes in a stable order. */
    @Synchronized
    fun evaluate(): List<Transition> {
        val now = clock.instant()
        prune(now)
        val transitions = mutableListOf<Transition>()
        for (rule in rules) {
            val windowStart = now.minus(rule.settings.window)
            for ((pair, deque) in samples) {
                if (pair.inbound != rule.inbound) continue
                val tracker = trackers[IncidentKey(rule.name, pair.service, pair.dependency)] ?: continue
                val inWindow = deque.filter { it.receivedAt.isAfter(windowStart) }
                tracker.update(rule.evaluate(inWindow), now)?.let(transitions::add)
            }
        }
        expireIdle(now)
        return transitions
    }

    @Synchronized
    fun snapshot(): List<IncidentView> = trackers.values.map { it.view() }

    private fun prune(now: Instant) {
        val oldest = now.minus(retention)
        for (deque in samples.values) {
            while (deque.isNotEmpty() && !deque.first().receivedAt.isAfter(oldest)) deque.removeFirst()
        }
    }

    /** Forgets pairs that sent nothing for [IDLE_EXPIRY], unless one of their incidents is DEGRADED or FIRING. */
    private fun expireIdle(now: Instant) {
        val iterator = samples.keys.iterator()
        while (iterator.hasNext()) {
            val pair = iterator.next()
            val keys = rulesFor(pair).map { IncidentKey(it.name, pair.service, pair.dependency) }
            val idle = keys.mapNotNull { trackers[it] }.all {
                it.state != IncidentState.DEGRADED && it.state != IncidentState.FIRING &&
                    it.lastSampleAt?.plus(IDLE_EXPIRY)?.isBefore(now) == true
            }
            if (idle) {
                iterator.remove()
                keys.forEach(trackers::remove)
            }
        }
    }

    private fun rulesFor(pair: Pair) = rules.filter { it.inbound == pair.inbound }

    private companion object {
        val IDLE_EXPIRY: Duration = Duration.ofMinutes(10)
    }
}
