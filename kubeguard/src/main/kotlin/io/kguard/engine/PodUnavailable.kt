package io.kguard.engine

import java.time.Duration
import java.time.Instant

/**
 * Workload unavailable: none of its current pods is ready, so its Service has no endpoint and every caller fails.
 * The trace rules can miss this. A pod that is not ready gets no traffic, so no failing requests are left to measure
 * (for example when the database check of the readiness probe fails). This rule reads the readiness itself.
 *
 * Met when the workload has current pods (reported within [staleAfter]) and none of them is ready. A pod is ready
 * when all its containers report ready. Finished containers (`Completed`, `Error`: Job pods) are left out, so a Job
 * that ran never counts as an unavailable workload. During a rollout the old pods stay ready until the new ones are,
 * so a normal rollout never meets the condition; a single new pod that starts slowly is covered by firing-duration.
 */
class PodUnavailableRule(
    override val settings: RuleSettings,
    private val staleAfter: Duration,
) : WorkloadRule<ContainerState> {
    override val name = NAME
    override val alertName = "KubeGuardWorkloadUnavailable"

    override fun evaluate(history: List<ContainerState>, now: Instant, feedAlive: Boolean): Evaluation {
        val latest = history.groupBy { it.pod to it.container }.mapValues { it.value.last() }.values
            .filter { it.receivedAt.isAfter(now.minus(staleAfter)) && it.reason !in FINISHED }
        val pods = latest.groupBy { it.pod }.mapValues { (_, containers) -> containers.filter { it.ready != null } }
            .filterValues { it.isNotEmpty() }
        if (pods.isEmpty()) {
            return Evaluation(Verdict.INSUFFICIENT_DATA, 0, null, 1.0,
                "no current pod reports in the last ${staleAfter.toSeconds()}s")
        }
        val ready = pods.filterValues { containers -> containers.all { it.ready == true } }.keys
        val notReady = pods.keys - ready
        val explanation = if (ready.isEmpty()) {
            "0 of ${pods.size} pods ready: no pod of the workload can take traffic (" +
                notReady.sorted().joinToString(", ") { pod -> "$pod not ready${reasonOf(pods.getValue(pod))}" } + ")"
        } else {
            "${ready.size} of ${pods.size} pods ready"
        }
        return Evaluation(
            verdict = if (ready.isEmpty()) Verdict.MET else Verdict.NOT_MET,
            sampleCount = pods.size,
            observed = ready.size.toDouble(),
            threshold = 1.0,
            explanation = explanation,
            byInstance = pods.keys.sorted().joinToString("; ") { pod ->
                if (pod in ready) "$pod: ready" else "$pod: not ready${reasonOf(pods.getValue(pod))}"
            },
        )
    }

    private fun reasonOf(containers: List<ContainerState>): String =
        containers.mapNotNull { it.reason }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty()

    companion object {
        const val NAME = "pod-unavailable"
        private val FINISHED = setOf("Completed", "Error")
    }
}
