package io.kguard.engine

import java.time.Duration
import java.time.Instant

/**
 * One pod as the OTel Collector's k8sobjects receiver reports it (the Pod object, pulled every 10s), reduced to what
 * the stuck-pod rules need. [createdAt] and [deletionAt] are the API server's `creationTimestamp` and
 * `deletionTimestamp` (the latter is when the grace period ends, not when the delete was requested).
 */
data class PodObject(
    override val receivedAt: Instant,
    val namespace: String,
    override val workload: String,
    val pod: String,
    val phase: String?,
    val createdAt: Instant?,
    val deletionAt: Instant?,
    val finalizers: List<String>,
    /** Why the pod is not running yet, e.g. `Unschedulable: 0/3 nodes are available…` or `ImagePullBackOff`. */
    val reason: String?,
) : WorkloadReport

/**
 * Pod stuck in Pending or Terminating. Met when a *current* pod of the workload (reported within [staleAfter]) has
 * been stuck for at least [stuckAfter]:
 *
 * - [Stuck.PENDING]: phase `Pending` (not deleted) since its creation, e.g. unschedulable or the image cannot be pulled
 * - [Stuck.TERMINATING]: deleted, and still there [stuckAfter] past the end of its grace period, e.g. held by a
 *   finalizer
 *
 * A pod that is gone no longer counts: while the source still reports other pods, a workload without current pods
 * is NOT_MET, so the incident resolves when the stuck pod disappears. If the source is silent, INSUFFICIENT_DATA.
 */
class PodStuckRule(
    override val settings: RuleSettings,
    private val stuck: Stuck,
    private val stuckAfter: Duration,
    private val staleAfter: Duration,
) : WorkloadRule<PodObject> {
    enum class Stuck(val ruleName: String, val alertName: String, val label: String) {
        PENDING("pod-pending", "KubeGuardPodStuckPending", "Pending"),
        TERMINATING("pod-terminating", "KubeGuardPodStuckTerminating", "Terminating"),
    }

    override val name = stuck.ruleName
    override val alertName = stuck.alertName

    override fun evaluate(history: List<PodObject>, now: Instant, feedAlive: Boolean): Evaluation {
        val current = history.groupBy { it.pod }.mapValues { it.value.last() }.values
            .filter { it.receivedAt.isAfter(now.minus(staleAfter)) }
        val threshold = stuckAfter.toSeconds().toDouble()
        if (current.isEmpty()) {
            return if (feedAlive) {
                Evaluation(Verdict.NOT_MET, 0, 0.0, threshold, "no current pods: the workload's pods are gone")
            } else {
                Evaluation(Verdict.INSUFFICIENT_DATA, 0, null, threshold,
                    "no current pod reports in the last ${staleAfter.toSeconds()}s")
            }
        }
        data class Finding(val pod: PodObject, val stuckFor: Duration)
        val findings = current.mapNotNull { pod -> since(pod)?.let { Finding(pod, Duration.between(it, now)) } }
        val stuckPods = findings.filter { it.stuckFor >= stuckAfter }
        val worst = (stuckPods.ifEmpty { findings }).maxByOrNull { it.stuckFor }
        val label = stuck.label
        val explanation = if (stuckPods.isEmpty()) {
            "no pod $label for ${stuckAfter.toSeconds()}s+ (${current.size} pods" +
                (worst?.let { ", longest ${it.pod.pod} ${it.stuckFor.toSeconds()}s" } ?: "") + ")"
        } else {
            "${stuckPods.size} of ${current.size} pods stuck $label: pod ${worst!!.pod.pod} " + describe(worst.pod, worst.stuckFor)
        }
        return Evaluation(
            verdict = if (stuckPods.isNotEmpty()) Verdict.MET else Verdict.NOT_MET,
            sampleCount = current.size,
            observed = (worst?.stuckFor?.toSeconds() ?: 0).toDouble(),
            threshold = threshold,
            explanation = explanation,
            byInstance = stuckPods.sortedBy { it.pod.pod }
                .joinToString("; ") { "${it.pod.pod}: $label ${it.stuckFor.toSeconds()}s" }
                .ifEmpty { null },
        )
    }

    private fun since(pod: PodObject): Instant? = when (stuck) {
        Stuck.PENDING -> pod.createdAt.takeIf { pod.phase == "Pending" && pod.deletionAt == null }
        Stuck.TERMINATING -> pod.deletionAt
    }

    private fun describe(pod: PodObject, stuckFor: Duration): String = when (stuck) {
        Stuck.PENDING -> "Pending for ${stuckFor.toSeconds()}s since creation" +
            (pod.reason?.let { ", $it" } ?: "")
        Stuck.TERMINATING -> "Terminating ${stuckFor.toSeconds()}s past its grace period" +
            (if (pod.finalizers.isNotEmpty()) ", held by finalizers ${pod.finalizers.joinToString(", ")}" else "") +
            (pod.phase?.let { ", phase $it" } ?: "")
    }
}
