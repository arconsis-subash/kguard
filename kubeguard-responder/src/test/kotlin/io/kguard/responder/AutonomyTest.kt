package io.kguard.responder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Shadow, approve and auto per action: what the responder does with an action the policy allows. */
class AutonomyTest {
    private val clock = MutableClock()
    private val cluster = FakeCluster()
    private val issues = mutableListOf<String>()
    private val policy = ActionPolicy(cluster, setOf("my-app"), "medium", Duration.ofMinutes(10))
    private val incident = alert().incidentId

    private fun responder(autonomy: String, dryRun: Boolean = false) = Responder(
        agent = { answer("restart-pod", "backend-a") },
        policy = policy,
        cluster = cluster,
        issues = { _, _, reason -> issues += reason; "https://github.com/x/y/issues/3" },
        executor = { it.run() },
        clock = clock,
        namespace = "my-namespace",
        services = setOf("my-app"),
        verifyTimeout = Duration.ofMinutes(4),
        dryRun = dryRun,
        autonomy = Autonomy.parse(autonomy),
        approvalTimeout = Duration.ofMinutes(15),
    )

    private fun Responder.send(vararg alerts: AmAlert) = onWebhook(AlertmanagerWebhook(alerts = alerts.toList()))

    @Test
    fun `shadow records the proposal, hands the incident to a human and changes nothing`() {
        val responder = responder("")
        responder.send(alert())
        val run = responder.runs().single()
        assertEquals(Phase.SHADOWED, run.phase)
        assertEquals(Autonomy.SHADOW, run.autonomy)
        assertEquals("restart-pod backend-a", run.decision)
        assertTrue(cluster.deleted.isEmpty())
        assertTrue(issues.single().startsWith("shadow mode: KubeGuard would restart-pod backend-a"))
        // No cooldown either: nothing was done.
        assertTrue(policy.decide(run.diagnosis!!, setOf("backend-a"), clock.now) is Decision.Execute)
    }

    @Test
    fun `dry run puts an auto action in shadow mode`() {
        val responder = responder("restart-pod=auto", dryRun = true)
        responder.send(alert())
        assertEquals(Phase.SHADOWED, responder.runs().single().phase)
        assertTrue(cluster.deleted.isEmpty())
    }

    @Test
    fun `approve waits for a human, then acts and verifies`() {
        val responder = responder("restart-pod=approve")
        responder.send(alert())
        val run = responder.runs().single()
        assertEquals(Phase.AWAITING_APPROVAL, run.phase)
        assertEquals(clock.now.plus(Duration.ofMinutes(15)), run.approvalDeadline)
        assertTrue(cluster.deleted.isEmpty())
        assertTrue(issues.single().startsWith("approval needed: KubeGuard proposes restart-pod backend-a"))

        clock.now = clock.now.plusSeconds(90)
        assertEquals(DecisionResult.DONE, responder.approve(incident, "console"))
        assertEquals(listOf("backend-a"), cluster.deleted)
        assertEquals(Phase.VERIFYING, run.phase)
        assertEquals(Autonomy.APPROVE, run.autonomy)
        assertEquals("console", run.decidedBy)

        responder.send(alert(status = "resolved"))
        assertEquals(Phase.FIXED, run.phase)
    }

    @Test
    fun `an approval is checked against the policy again`() {
        val responder = responder("restart-pod=approve")
        responder.send(alert())
        policy.recordAction("my-app", clock.now) // something else acted while it waited
        responder.approve(incident, "console")
        val run = responder.runs().single()
        assertEquals(Phase.ESCALATED, run.phase)
        assertTrue(cluster.deleted.isEmpty())
        assertTrue(issues.last().startsWith("approved by console, but the policy now refuses: Deployment my-app was already acted on"))
    }

    @Test
    fun `reject escalates without acting`() {
        val responder = responder("restart-pod=approve")
        responder.send(alert())
        assertEquals(DecisionResult.DONE, responder.reject(incident, "console"))
        assertEquals(Phase.ESCALATED, responder.runs().single().phase)
        assertTrue(cluster.deleted.isEmpty())
        assertEquals("rejected by console: restart-pod backend-a", issues.last())
        assertEquals(DecisionResult.NOT_WAITING, responder.approve(incident, "console"))
    }

    @Test
    fun `no approval in time escalates`() {
        val responder = responder("restart-pod=approve")
        responder.send(alert())
        clock.now = clock.now.plus(Duration.ofMinutes(14))
        responder.checkTimeouts()
        assertEquals(Phase.AWAITING_APPROVAL, responder.runs().single().phase)
        clock.now = clock.now.plus(Duration.ofMinutes(2))
        responder.checkTimeouts()
        assertEquals(Phase.ESCALATED, responder.runs().single().phase)
        assertEquals("nobody approved restart-pod backend-a within PT15M", issues.last())
        assertTrue(cluster.deleted.isEmpty())
    }

    @Test
    fun `an incident that resolves while waiting needs no approval any more`() {
        val responder = responder("restart-pod=approve")
        responder.send(alert())
        responder.send(alert(status = "resolved"))
        assertEquals(Phase.RESOLVED_BEFORE_ACTION, responder.runs().single().phase)
        assertEquals(DecisionResult.NOT_WAITING, responder.approve(incident, "console"))
        assertEquals(DecisionResult.NOT_FOUND, responder.approve("no-such-incident", "console"))
        assertTrue(cluster.deleted.isEmpty())
    }

    @Test
    fun `each action has its own level`() {
        val responder = responder("restart-pod=auto,rollback=approve")
        responder.send(alert())
        assertEquals(Phase.VERIFYING, responder.runs().single().phase)
        assertEquals(listOf("backend-a"), cluster.deleted)
    }

    @Test
    fun `parses the autonomy setting and rejects typos`() {
        assertEquals(mapOf(Action.RESTART_POD to Autonomy.AUTO, Action.ROLLBACK to Autonomy.APPROVE),
            Autonomy.parse(" restart-pod=auto , rollback=APPROVE "))
        assertEquals(emptyMap<Action, Autonomy>(), Autonomy.parse(""))
        assertThrows(IllegalArgumentException::class.java) { Autonomy.parse("restart-pods=auto") }
        assertThrows(IllegalArgumentException::class.java) { Autonomy.parse("restart-pod=yes") }
        assertThrows(IllegalArgumentException::class.java) { Autonomy.parse("none=auto") }
    }
}
