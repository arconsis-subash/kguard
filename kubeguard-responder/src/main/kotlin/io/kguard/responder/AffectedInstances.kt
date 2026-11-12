package io.kguard.responder

/**
 * The pods KubeGuard saw failing or slow, from its `by_instance` annotation, e.g.
 * `pod-a: 10/10 calls failed; pod-b: 0/10 calls failed` or `pod-a: p95 1600 ms over 20 calls; pod-b: p95 3 ms ...`.
 */
object AffectedInstances {
    private val failed = Regex("^(\\S+): (\\d+)/(\\d+) calls failed$")
    private val restarts = Regex("^(\\S+): (\\d+) restarts in \\S+(.*)$")
    private val latency = Regex("^(\\S+): p95 (\\d+) ms over \\d+ (?:calls|requests)$")

    fun of(alert: AmAlert): Set<String> {
        val byInstance = alert.annotations["by_instance"] ?: return emptySet()
        val threshold = alert.annotations["threshold"]?.toDoubleOrNull() ?: Double.MAX_VALUE
        return byInstance.split(";").map { it.trim() }.mapNotNull { entry ->
            failed.matchEntire(entry)?.let { m -> m.groupValues[1].takeIf { m.groupValues[2].toInt() > 0 } }
                ?: restarts.matchEntire(entry)?.let { m ->
                    m.groupValues[1].takeIf { m.groupValues[2].toInt() > 0 || "CrashLoopBackOff" in m.groupValues[3] }
                }
                ?: latency.matchEntire(entry)?.let { m -> m.groupValues[1].takeIf { m.groupValues[2].toDouble() > threshold } }
        }.toSet()
    }
}
