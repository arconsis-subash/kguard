package io.kguard.responder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var now: Instant = Instant.parse("2026-10-02T08:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

/** Two backend pods owned by Deployment my-app, and the PostgreSQL StatefulSet pod. */
class FakeCluster : Cluster {
    val deleted = mutableListOf<String>()
    val restarted = mutableListOf<String>()
    val rolledBack = mutableListOf<String>()
    var previousRevision: Long? = 3
    var stuck = true
    override fun deploymentOfPod(pod: String) = when (pod) {
        "backend-a", "backend-b" -> "my-app"
        else -> null // my-app-postgresql-0 is owned by a StatefulSet
    }
    override fun deploymentExists(name: String) = name == "my-app"
    override fun deletePod(pod: String) { deleted += pod }
    override fun rolloutRestart(deployment: String) { restarted += deployment }
    override fun previousRevision(deployment: String) = previousRevision
    override fun rolloutStuck(deployment: String) = stuck
    override fun rollback(deployment: String) { rolledBack += deployment }
}

fun answer(action: String, target: String?, confidence: String = "high", fixBy: String = "kagent") = """
    Symptom: one pod cannot reach the database.
    ```json
    {"summary": "backend-a cannot reach PostgreSQL", "root_cause": "pod-local network problem",
     "confidence": "$confidence", "action": "$action", "target": ${target?.let { "\"$it\"" } ?: "null"},
     "fix_by": "$fixBy", "evidence": ["backend-a: 20/20 calls failed"]}
    ```
""".trimIndent()

fun alert(status: String = "firing", id: String = "dependency-connectivity:svc->db:20261002T080000Z") = AmAlert(
    status = status,
    labels = mapOf("alertname" to "KubeGuardDependencyConnectivityFailure", "service" to "my-app",
        "dependency" to "my-app-postgresql", "source" to "kubeguard", "severity" to "critical"),
    annotations = mapOf("incident_id" to id, "description" to "20 of 40 calls could not reach the dependency",
        "by_instance" to "backend-a: 20/20 calls failed; backend-b: 0/20 calls failed", "threshold" to "0.3"),
    fingerprint = "f1",
)

class ResponderTest {
    private val clock = MutableClock()
    private val cluster = FakeCluster()
    private val issues = mutableListOf<String>()
    private var agentAnswer = answer("restart-pod", "backend-a")
    private val prompts = mutableListOf<String>()

    private val responder = Responder(
        agent = { prompt -> prompts += prompt; agentAnswer },
        policy = ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10)),
        cluster = cluster,
        issues = { _, _, reason -> issues += reason; "https://github.com/x/y/issues/1" },
        executor = { it.run() },
        clock = clock,
        namespace = "my-namespace",
        services = setOf("my-app"),
        verifyTimeout = Duration.ofMinutes(4),
        reportOnlyAlerts = setOf("KubeGuardPodStuckPending", "KubeGuardPodStuckTerminating"),
    )

    private fun send(vararg alerts: AmAlert) = responder.onWebhook(AlertmanagerWebhook(alerts = alerts.toList()))
    private fun run() = responder.runs().single()

    @Test
    fun `restarts the affected pod and counts it as fixed when KubeGuard resolves`() {
        send(alert())
        assertEquals(listOf("backend-a"), cluster.deleted)
        assertEquals(Phase.VERIFYING, run().phase)
        assertTrue(prompts.single().contains("backend-a: 20/20 calls failed"))

        clock.now = clock.now.plusSeconds(60)
        send(alert(status = "resolved"))
        assertEquals(Phase.FIXED, run().phase)
        assertTrue(issues.single().contains("clears the symptom, not the cause"), "follow-up issue for the cause")
        assertTrue(issues.single().contains("1 of 3"))
    }

    @Test
    fun `a fix that keeps coming back goes to a human instead of another restart`() {
        repeat(3) { i ->
            clock.now = clock.now.plus(Duration.ofMinutes(30))
            send(alert(id = "leak-$i"))
            send(alert(id = "leak-$i", status = "resolved"))
        }
        assertEquals(listOf("backend-a", "backend-a", "backend-a"), cluster.deleted)
        assertTrue(issues.last().contains("3 of 3"))

        clock.now = clock.now.plus(Duration.ofMinutes(30))
        send(alert(id = "leak-3"))
        assertEquals(3, cluster.deleted.size, "no fourth restart")
        assertEquals(Phase.ESCALATED, responder.runs().last().phase)
        assertTrue(issues.last().contains("acted on 3 times"))

        clock.now = clock.now.plus(Duration.ofHours(24))
        send(alert(id = "leak-4"))
        assertEquals(4, cluster.deleted.size, "acts again once the window has passed")
    }

    @Test
    fun `cause and symptom alerts of one service share one run and one action`() {
        val symptom = alert(id = "service-latency:svc->POST /api/echo:20261002T080005Z").copy(
            labels = alert().labels + ("alertname" to "KubeGuardServiceLatencyHigh") + ("dependency" to "POST /api/echo"),
        )
        send(symptom, alert())
        assertEquals(1, responder.runs().size)
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().indexOf("KubeGuardDependencyConnectivityFailure") <
            prompts.single().indexOf("KubeGuardServiceLatencyHigh"), "cause first")
        assertEquals(listOf("backend-a"), cluster.deleted)

        send(alert(status = "resolved"))
        assertEquals(Phase.VERIFYING, run().phase, "still waiting for the symptom")
        send(symptom.copy(status = "resolved"))
        assertEquals(Phase.FIXED, run().phase)
        assertEquals(1, issues.size, "one follow-up issue for the run")
    }

    @Test
    fun `an alert firing later while the run is open is attached`() {
        send(alert())
        send(alert(id = "later").copy(labels = alert().labels + ("alertname" to "KubeGuardServiceLatencyHigh")))
        assertEquals(1, responder.runs().size)
        assertEquals(1, prompts.size)
        assertEquals(2, run().alerts.size)
    }

    @Test
    fun `repeated firing notifications start only one run`() {
        send(alert())
        send(alert())
        clock.now = clock.now.plusSeconds(30)
        send(alert())
        assertEquals(1, responder.runs().size)
        assertEquals(1, prompts.size)
        assertEquals(listOf("backend-a"), cluster.deleted)
    }

    @Test
    fun `no recovery after the action escalates`() {
        send(alert())
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        responder.checkTimeouts()
        assertEquals(Phase.ESCALATED, run().phase)
        assertTrue(issues.single().contains("did not report recovery"))
    }

    @Test
    fun `agent says it cannot be fixed here - issue, no action`() {
        agentAnswer = answer("none", null, fixBy = "human")
        send(alert())
        assertTrue(cluster.deleted.isEmpty())
        assertEquals(Phase.ESCALATED, run().phase)
        assertTrue(issues.single().contains("no allowed action"))
    }

    @Test
    fun `without the AI the default action still goes through the policy and is verified`() {
        val responder = Responder(
            agent = { error("kagent unreachable") },
            policy = ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10)),
            cluster = cluster,
            issues = { _, _, reason -> issues += reason; "" },
            executor = { it.run() },
            clock = clock,
            namespace = "my-namespace",
            services = setOf("my-app"),
            verifyTimeout = Duration.ofMinutes(4),
            defaultActions = mapOf("KubeGuardDependencyConnectivityFailure" to Action.RESTART_POD),
        )
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(alert())))
        assertEquals(listOf("backend-a"), cluster.deleted, "the one affected pod")
        val run = responder.runs().single()
        assertTrue(run.timeline.any { "WITHOUT DIAGNOSIS: the diagnosis agent failed: kagent unreachable" in it })
        assertTrue(run.diagnosis!!.summary.startsWith("no AI diagnosis"))

        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(alert(status = "resolved"))))
        assertEquals(Phase.FIXED, run.phase)
    }

    @Test
    fun `without the AI and without a default, or with several affected pods, it escalates`() {
        fun responder(defaults: Map<String, Action>) = Responder({ "no json here" },
            ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10)), cluster,
            { _, _, r -> issues += r; "" }, { it.run() }, clock, "my-namespace",
            setOf("my-app"), Duration.ofMinutes(4), defaultActions = defaults)

        responder(emptyMap()).onWebhook(AlertmanagerWebhook(alerts = listOf(alert())))
        assertTrue(issues.last().contains("no valid diagnosis block"))

        val bothFailing = alert().copy(annotations = alert().annotations +
            ("by_instance" to "backend-a: 20/20 calls failed; backend-b: 20/20 calls failed"))
        responder(mapOf("KubeGuardDependencyConnectivityFailure" to Action.RESTART_POD))
            .onWebhook(AlertmanagerWebhook(alerts = listOf(bothFailing)))
        assertTrue(issues.last().contains("needs exactly one affected pod, KubeGuard reported 2"))
        assertTrue(cluster.deleted.isEmpty())
    }

    @Test
    fun `default actions are parsed strictly`() {
        assertEquals(mapOf("KubeGuardMemoryExhaustionPredicted" to Action.ROLLOUT_RESTART),
            DefaultActions.parse(" KubeGuardMemoryExhaustionPredicted = rollout-restart "))
        assertTrue(runCatching { DefaultActions.parse("KubeGuardX=none") }.isFailure)
        assertTrue(runCatching { DefaultActions.parse("KubeGuardX=scale") }.isFailure)
    }

    @Test
    fun `answer without a diagnosis block escalates`() {
        agentAnswer = "I think the database is down."
        send(alert())
        assertEquals(Phase.ESCALATED, run().phase)
        assertTrue(cluster.deleted.isEmpty())
    }

    @Test
    fun `ignores alerts of other services and other sources`() {
        send(alert().copy(labels = alert().labels + ("service" to "mock-backend")))
        send(alert().copy(labels = alert().labels + ("source" to "grafana")))
        assertTrue(responder.runs().isEmpty())
    }

    @Test
    fun `a stuck pod of any workload is diagnosed and escalated, never acted on`() {
        agentAnswer = answer("restart-pod", "stuck-pending", fixBy = "config")
        send(AmAlert(
            labels = mapOf("alertname" to "KubeGuardPodStuckPending", "service" to "stuck-pending",
                "dependency" to "pods", "source" to "kubeguard", "severity" to "warning"),
            annotations = mapOf("incident_id" to "pod-pending:stuck-pending->pods:20261002T080000Z",
                "description" to "pod stuck-pending Pending for 90s since creation, Unschedulable"),
            status = "firing",
        ))
        assertTrue(prompts.single().contains("Unschedulable"))
        assertTrue(cluster.deleted.isEmpty() && cluster.restarted.isEmpty() && cluster.rolledBack.isEmpty())
        assertEquals(Phase.ESCALATED, run().phase)
        assertTrue(issues.single().contains("report only"), issues.single())
    }

    @Test
    fun `resolved before the action ends the run without acting`() {
        val held = mutableListOf<Runnable>()
        val responder = Responder({ agentAnswer }, ActionPolicy(cluster, setOf("my-app"), "medium",
            Duration.ofMinutes(10)), cluster, { _, _, r -> issues += r; "" }, { held += it }, clock,
            "my-namespace", setOf("my-app"), Duration.ofMinutes(4))
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(alert())))
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(alert(status = "resolved"))))
        held.forEach { it.run() } // the diagnosis finishes after the incident is already resolved
        assertEquals(Phase.RESOLVED_BEFORE_ACTION, responder.runs().single().phase)
        assertTrue(cluster.deleted.isEmpty())
        assertTrue(issues.isEmpty())
    }
}

class RollbackTest {
    private val clock = MutableClock()
    private val cluster = FakeCluster()
    private val issues = mutableListOf<String>()
    private val responder = Responder(
        agent = { answer("rollback", "my-app", fixBy = "config") },
        policy = ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10)),
        cluster = cluster,
        issues = { _, _, reason -> issues += reason; "https://github.com/x/y/issues/2" },
        executor = { it.run() },
        clock = clock,
        namespace = "my-namespace",
        services = setOf("my-app"),
        verifyTimeout = Duration.ofMinutes(4),
    )
    private val crashloop = alert(id = "pod-crashloop:svc->pods:1").copy(
        labels = alert().labels + ("alertname" to "KubeGuardPodCrashLooping") + ("dependency" to "pods"),
        annotations = mapOf("incident_id" to "pod-crashloop:svc->pods:1",
            "by_instance" to "backend-a: 0 restarts in 5m [ReplicaSet r1]; backend-new: 4 restarts in 5m (CrashLoopBackOff) [ReplicaSet r2]"),
    )

    @Test
    fun `crash loop after a deploy is rolled back, verified, and still reported for the repo`() {
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(crashloop)))
        assertEquals(listOf("my-app"), cluster.rolledBack)
        assertEquals(Phase.VERIFYING, responder.runs().single().phase)

        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(crashloop.copy(status = "resolved"))))
        assertEquals(Phase.FIXED, responder.runs().single().phase)
        assertTrue(issues.single().contains("rollback"), "follow-up issue for the repo")
    }

    @Test
    fun `no previous revision - no rollback, issue`() {
        cluster.previousRevision = null
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(crashloop)))
        assertTrue(cluster.rolledBack.isEmpty())
        assertEquals(Phase.ESCALATED, responder.runs().single().phase)
        assertTrue(issues.single().contains("no previous revision"))
    }

    @Test
    fun `a failing cluster check escalates instead of hanging`() {
        val broken = object : Cluster by cluster {
            override fun previousRevision(deployment: String): Long? = error("replicasets.apps is forbidden")
        }
        val responder = Responder({ answer("rollback", "my-app", fixBy = "config") },
            ActionPolicy(broken, setOf("my-app"), "medium", Duration.ofMinutes(10)), broken,
            { _, _, r -> issues += r; "" }, { it.run() }, clock, "my-namespace",
            setOf("my-app"), Duration.ofMinutes(4))
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(crashloop)))
        assertEquals(Phase.ESCALATED, responder.runs().single().phase)
        assertTrue(issues.single().contains("policy check failed"))
    }

    @Test
    fun `pods crashing without a stuck rollout are not rolled back`() {
        cluster.stuck = false
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(crashloop)))
        assertTrue(cluster.rolledBack.isEmpty())
        assertTrue(issues.single().contains("not stuck"))
    }

    @Test
    fun `crashing pods are read from KubeGuard's restart breakdown`() {
        assertEquals(setOf("backend-new"), AffectedInstances.of(crashloop))
    }
}

class ActionPolicyTest {
    private val cluster = FakeCluster()
    private val policy = ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10))
    private val now = Instant.parse("2026-10-02T08:00:00Z")
    private val affected = setOf("backend-a")

    private fun decide(action: String, target: String?, confidence: String = "high", at: Instant = now) =
        policy.decide(DiagnosisParser.parse(answer(action, target, confidence))!!, affected, at)

    @Test
    fun `allowed restart of an affected backend pod`() {
        assertEquals(Decision.Execute(Action.RESTART_POD, "backend-a", "my-app"),
            decide("restart-pod", "backend-a"))
    }

    @Test
    fun `never the database, never an unaffected pod, never unknown actions`() {
        assertTrue(policy.decide(DiagnosisParser.parse(answer("restart-pod", "my-app-postgresql-0"))!!,
            setOf("my-app-postgresql-0"), now) is Decision.Escalate)
        assertTrue(decide("restart-pod", "backend-b") is Decision.Escalate)
        assertTrue(decide("scale", "my-app") is Decision.Escalate)
        assertTrue(decide("rollout-restart", "my-app-postgresql") is Decision.Escalate)
        assertTrue(decide("restart-pod", null) is Decision.Escalate)
    }

    @Test
    fun `a deployment outside the allow-list is refused before the cluster is read`() {
        // The responder's Role cannot read other Deployments; a lookup would fail with 403.
        val strict = object : Cluster by cluster {
            override fun deploymentExists(name: String): Boolean = error("must not read Deployment $name")
        }
        val policy = ActionPolicy(strict, setOf("my-app"), "medium", Duration.ofMinutes(10))
        for (action in listOf("rollout-restart", "rollback")) {
            val decision = policy.decide(DiagnosisParser.parse(answer(action, "kagent-controller"))!!, affected, now)
            assertEquals(Decision.Escalate("Deployment kagent-controller is not in the allow-list [my-app]"),
                decision)
        }
    }

    @Test
    fun `low confidence is not enough`() {
        assertTrue(decide("restart-pod", "backend-a", confidence = "low") is Decision.Escalate)
        assertTrue(decide("restart-pod", "backend-a", confidence = "medium") is Decision.Execute)
    }

    @Test
    fun `at most three actions per deployment within the window`() {
        repeat(3) { policy.recordAction("my-app", now.plus(Duration.ofHours(it.toLong()))) }
        val later = now.plus(Duration.ofHours(5))
        assertTrue(decide("rollout-restart", "my-app", at = later) is Decision.Escalate)
        assertTrue(decide("rollout-restart", "my-app", at = now.plus(Duration.ofHours(24)).plusSeconds(1)) is Decision.Execute)
    }

    @Test
    fun `one action per deployment within the cooldown`() {
        policy.recordAction("my-app", now)
        assertTrue(decide("rollout-restart", "my-app", at = now.plusSeconds(300)) is Decision.Escalate)
        assertTrue(decide("rollout-restart", "my-app", at = now.plusSeconds(601)) is Decision.Execute)
    }
}

class ParsingTest {
    @Test
    fun `parses the last json block and tolerates extra fields`() {
        val text = "first ```json\n{\"action\": \"none\"}\n``` then\n```json\n{\"action\": \"restart-pod\", \"target\": \"p\", " +
            "\"confidence\": \"high\", \"extra\": 1}\n```"
        val d = DiagnosisParser.parse(text)!!
        assertEquals("restart-pod", d.action)
        assertEquals("p", d.target)
        assertNull(DiagnosisParser.parse("no block"))
        // seen live: the model left out the closing fence
        assertEquals("none", DiagnosisParser.parse("4. none\n\n```json\n{\"action\": \"none\",\n \"target\": null}")!!.action)
    }

    @Test
    fun `affected instances from KubeGuard's per-pod breakdown`() {
        assertEquals(setOf("backend-a"), AffectedInstances.of(alert()))
        val latency = alert().copy(annotations = mapOf("threshold" to "250.0",
            "by_instance" to "backend-a: p95 412 ms over 20 calls; backend-b: p95 3 ms over 20 calls"))
        assertEquals(setOf("backend-a"), AffectedInstances.of(latency))
    }

    @Test
    fun `issue labels route code and config to the coding agent, the rest to a human`() {
        assertEquals(listOf("kagent-diagnosis", "fix issue agent"), IssueText.labels(Diagnosis(fixBy = "code")))
        assertEquals(listOf("kagent-diagnosis", "fix issue human"), IssueText.labels(Diagnosis(fixBy = "human")))
        assertEquals(listOf("kagent-diagnosis", "fix issue human"), IssueText.labels(null))
    }
}

class PilotReportTest {
    private val clock = MutableClock()
    private val cluster = FakeCluster()
    private val responder = Responder(
        agent = { answer("restart-pod", "backend-a") },
        policy = ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10)),
        cluster = cluster,
        issues = { _, _, _ -> "https://github.com/x/y/issues/1" },
        executor = { it.run() },
        clock = clock,
        namespace = "my-namespace",
        services = setOf("my-app"),
        verifyTimeout = Duration.ofMinutes(4),
        autonomy = mapOf(Action.RESTART_POD to Autonomy.SHADOW),
    )

    private fun send(vararg alerts: AmAlert) = responder.onWebhook(AlertmanagerWebhook(alerts = alerts.toList()))

    @Test
    fun `counts what the responder would have done and how often a human judged it right`() {
        send(alert(id = "a"))
        clock.now = clock.now.plus(Duration.ofHours(1))
        send(alert(id = "b"))
        assertEquals(Phase.SHADOWED, responder.runs().last().phase)
        assertTrue(cluster.deleted.isEmpty(), "shadow mode changes nothing")

        assertEquals(DecisionResult.DONE, responder.judge("a", Verdict.RIGHT, "oncall"))
        assertEquals(DecisionResult.DONE, responder.judge("b", Verdict.WRONG, "oncall"))
        assertEquals(DecisionResult.NOT_FOUND, responder.judge("x", Verdict.RIGHT, "oncall"))

        val row = PilotReport.of(responder.runs()).rows.single()
        assertEquals("KubeGuardDependencyConnectivityFailure", row.alert)
        assertEquals("restart-pod", row.action)
        assertEquals(2, row.incidents)
        assertEquals(2, row.shadowed)
        assertEquals(0, row.acted)
        assertEquals(1, row.judgedRight)
        assertEquals(1, row.judgedWrong)

        val csv = PilotReport.csv(responder.runs()).lines()
        assertTrue(csv[0].startsWith("incident_id,received_at,alert"))
        assertTrue(csv[1].contains(",SHADOWED,SHADOW,restart-pod backend-a,false,RIGHT,oncall,"))
    }

    @Test
    fun `only finished runs can be judged`() {
        val open = Responder({ answer("restart-pod", "backend-a") },
            ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10)), cluster,
            { _, _, _ -> "" }, { it.run() }, clock, "my-namespace", setOf("my-app"),
            Duration.ofMinutes(4))
        open.onWebhook(AlertmanagerWebhook(alerts = listOf(alert(id = "c"))))
        assertEquals(Phase.VERIFYING, open.runs().single().phase)
        assertEquals(DecisionResult.NOT_WAITING, open.judge("c", Verdict.RIGHT, "oncall"))
    }
}

class AuditTest {
    private val clock = MutableClock()
    private val cluster = FakeCluster()
    private val events = mutableListOf<Pair<AuditReason, String>>()

    private fun responder(autonomy: Map<Action, Autonomy>, audit: AuditLog = AuditLog { run, reason, message ->
        events += reason to "${run.deployment ?: run.service}: $message"
    }) = Responder(
        agent = { answer("restart-pod", "backend-a") },
        policy = ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10)),
        cluster = cluster,
        issues = { _, _, _ -> "" },
        executor = { it.run() },
        clock = clock,
        namespace = "my-namespace",
        services = setOf("my-app"),
        verifyTimeout = Duration.ofMinutes(4),
        autonomy = autonomy,
        audit = audit,
    )

    @Test
    fun `every action and its verification are recorded on the deployment`() {
        val responder = responder(mapOf(Action.RESTART_POD to Autonomy.AUTO))
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(alert())))
        responder.onWebhook(AlertmanagerWebhook(alerts = listOf(alert(status = "resolved"))))
        assertEquals(listOf(AuditReason.KGuardAction, AuditReason.KGuardVerified), events.map { it.first })
        assertTrue(events[0].second.startsWith("my-app: restart-pod backend-a (auto); diagnosis: backend-a cannot reach PostgreSQL"))
    }

    @Test
    fun `shadow and escalation are recorded too, and a failing audit write changes nothing`() {
        responder(emptyMap()).onWebhook(AlertmanagerWebhook(alerts = listOf(alert())))
        assertEquals(listOf(AuditReason.KGuardShadow, AuditReason.KGuardEscalated), events.map { it.first })

        val failing = responder(mapOf(Action.RESTART_POD to Autonomy.AUTO), audit = { _, _, _ -> error("forbidden") })
        failing.onWebhook(AlertmanagerWebhook(alerts = listOf(alert(id = "other"))))
        assertEquals(listOf("backend-a"), cluster.deleted, "the action still ran")
        assertTrue(failing.runs().single().timeline.any { "could not write the audit event KGuardAction: forbidden" in it })
    }
}
