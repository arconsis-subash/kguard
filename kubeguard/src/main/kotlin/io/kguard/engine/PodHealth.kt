package io.kguard.engine

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One report about a pod or container of a workload, received at [receivedAt] by KubeGuard. */
interface WorkloadReport {
    val receivedAt: Instant

    /** Deployment (or other owner) the pod belongs to; a bare pod is its own workload. */
    val workload: String
}

/** A deterministic incident condition over the recent reports of one workload. */
interface WorkloadRule<T : WorkloadReport> : RuleInfo {
    /**
     * [history] are all reports of one workload within the window, oldest first. [feedAlive]: the source sent any
     * report (for any workload) recently, so a workload without current reports has no pods any more.
     */
    fun evaluate(history: List<T>, now: Instant, feedAlive: Boolean): Evaluation
}

/**
 * State of one container as the OTel Collector's k8s_cluster receiver reports it (metrics `k8s.container.restarts`,
 * `k8s.container.ready`, `k8s.container.status.reason`), received at [receivedAt] by KubeGuard.
 */
data class ContainerState(
    override val receivedAt: Instant,
    val namespace: String,
    override val workload: String,
    val replicaSet: String?,
    val pod: String,
    val container: String,
    val image: String?,
    val restarts: Long?,
    val ready: Boolean?,
    /** Current waiting/terminated reason, e.g. `CrashLoopBackOff`; null while running normally. */
    val reason: String?,
    val lastTerminatedReason: String?,
) : WorkloadReport

/**
 * Pod crash loop: a pod of a workload restarts again and again. Met when a *current* container (reported within
 * [staleAfter]) restarted at least [minRestarts] times within the window, or is waiting in `CrashLoopBackOff`.
 * Pods that are gone (deleted, replaced by a rollback) no longer count, so recovery is seen as soon as the
 * crashing pod disappears.
 */
class PodCrashLoopRule(
    override val settings: RuleSettings,
    private val minRestarts: Long,
    private val staleAfter: Duration,
) : WorkloadRule<ContainerState> {
    override val name = NAME
    override val alertName = "KubeGuardPodCrashLooping"

    override fun evaluate(history: List<ContainerState>, now: Instant, feedAlive: Boolean): Evaluation {
        val byContainer = history.groupBy { it.pod to it.container }
        val current = byContainer.filterValues { it.last().receivedAt.isAfter(now.minus(staleAfter)) }
        if (current.isEmpty()) {
            return Evaluation(Verdict.INSUFFICIENT_DATA, 0, null, minRestarts.toDouble(),
                "no current pod reports in the last ${staleAfter.toSeconds()}s")
        }
        data class Finding(val pod: String, val container: String, val restarts: Long, val last: ContainerState)
        val findings = current.map { (id, reports) ->
            val counts = reports.mapNotNull { it.restarts }
            val increase = if (counts.isEmpty()) 0 else (counts.last() - counts.min()).coerceAtLeast(0)
            Finding(id.first, id.second, increase, reports.last())
        }
        val crashing = findings.filter { it.restarts >= minRestarts || it.last.reason == CRASH_LOOP }
        val worst = crashing.maxByOrNull { it.restarts } ?: findings.maxBy { it.restarts }
        val window = settings.window.toMinutes()
        val explanation = if (crashing.isEmpty()) {
            "no pod restarted ${minRestarts}+ times in the last ${window}m (max ${worst.restarts}, ${findings.size} containers)"
        } else {
            "${crashing.size} of ${findings.size} containers crash-looping: pod ${worst.pod} container ${worst.container} " +
                "restarted ${worst.restarts} times in the last ${window}m" +
                (worst.last.reason?.let { ", now $it" } ?: "") +
                (worst.last.lastTerminatedReason?.takeIf { it.isNotBlank() }?.let { ", last terminated: $it" } ?: "") +
                (worst.last.image?.let { ", image $it" } ?: "") +
                (worst.last.replicaSet?.let { ", ReplicaSet $it" } ?: "")
        }
        return Evaluation(
            verdict = if (crashing.isNotEmpty()) Verdict.MET else Verdict.NOT_MET,
            sampleCount = findings.size,
            observed = worst.restarts.toDouble(),
            threshold = minRestarts.toDouble(),
            explanation = explanation,
            byInstance = findings.sortedBy { it.pod }.joinToString("; ") {
                "${it.pod}: ${it.restarts} restarts in ${window}m" + (it.last.reason?.let { r -> " ($r)" } ?: "") +
                    (it.last.replicaSet?.let { rs -> " [ReplicaSet $rs]" } ?: "")
            },
        )
    }

    companion object {
        const val NAME = "pod-crashloop"
        const val CRASH_LOOP = "CrashLoopBackOff"
    }
}

/**
 * Keeps the reports per workload for the longest window of its [rules] and runs each rule through the same incident
 * state machine as the trace rules. Incident key: rule name, service = workload, dependency = `pods`. [staleAfter]
 * decides whether the source itself is still reporting (see [WorkloadRule.evaluate]).
 */
class PodHealthEngine<T : WorkloadReport>(
    private val rules: List<WorkloadRule<T>>,
    private val clock: Clock,
    private val staleAfter: Duration,
) {
    private val history = LinkedHashMap<String, ArrayDeque<T>>()
    private val trackers = LinkedHashMap<IncidentKey, IncidentTracker>()
    private val window = rules.maxOf { it.settings.window }
    private var lastReportAt: Instant? = null

    @Synchronized
    fun record(states: List<T>) {
        for (state in states) {
            history.getOrPut(state.workload) { ArrayDeque() }.addLast(state)
            for (rule in rules) {
                trackers.getOrPut(key(rule, state.workload)) { IncidentTracker(key(rule, state.workload), rule, clock.instant()) }
                    .lastSampleAt = state.receivedAt
            }
            lastReportAt = state.receivedAt
        }
    }

    @Synchronized
    fun evaluate(): List<Transition> {
        val now = clock.instant()
        val feedAlive = lastReportAt?.isAfter(now.minus(staleAfter)) == true
        val transitions = mutableListOf<Transition>()
        val iterator = history.entries.iterator()
        while (iterator.hasNext()) {
            val (workload, deque) = iterator.next()
            while (deque.isNotEmpty() && !deque.first().receivedAt.isAfter(now.minus(window))) deque.removeFirst()
            var active = false
            for (rule in rules) {
                val oldest = now.minus(rule.settings.window)
                val tracker = trackers.getValue(key(rule, workload))
                tracker.update(rule.evaluate(deque.filter { it.receivedAt.isAfter(oldest) }, now, feedAlive), now)
                    ?.let(transitions::add)
                active = active || tracker.state == IncidentState.FIRING || tracker.state == IncidentState.DEGRADED
            }
            if (deque.isEmpty() && !active) {
                iterator.remove()
                rules.forEach { trackers.remove(key(it, workload)) }
            }
        }
        return transitions
    }

    @Synchronized
    fun snapshot(): List<IncidentView> = trackers.values.map { it.view() }

    private fun key(rule: RuleInfo, workload: String) = IncidentKey(rule.name, workload, DEPENDENCY)

    companion object {
        const val DEPENDENCY = "pods"
    }
}
