package io.kguard.responder

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

/** All `responder.*` settings; defaults are in application.properties. */
@ConfigMapping(prefix = "responder")
interface ResponderConfig {
    /** Namespace the responder diagnoses and acts in. */
    fun namespace(): String

    /** Only alerts for these `service` labels are handled. */
    fun services(): Set<String>

    /** Alerts handled for any `service`, diagnosed and always escalated: no allowed action fixes them. */
    fun reportOnlyAlerts(): Set<String>

    /** Log the action instead of executing it. */
    @WithDefault("false")
    fun dryRun(): Boolean

    fun verifyTimeout(): Duration

    /** Autonomy level per action, e.g. `restart-pod=auto,rollback=approve`; unlisted actions stay in shadow mode. */
    fun autonomy(): Optional<String>

    /** Action per alert when the diagnosis agent fails, e.g. `KubeGuardMemoryExhaustionPredicted=rollout-restart`. Empty: escalate. */
    fun defaultActions(): Optional<String>

    /** How long a proposal in approve mode waits for a human before the incident is escalated. */
    @WithDefault("15m")
    fun approvalTimeout(): Duration

    /** Open a follow-up issue after every verified fix: the cause is still in the code, config or repo. */
    @WithDefault("true")
    fun reportFixes(): Boolean

    fun policy(): Policy

    fun kagent(): Kagent

    fun github(): GitHub

    interface Policy {
        fun allowedDeployments(): Set<String>

        /** low, medium or high. */
        fun minConfidence(): String

        fun cooldown(): Duration

        /** At most this many actions per Deployment within [actionWindow]; beyond that the incident is escalated. */
        @WithDefault("3")
        fun maxActions(): Int

        @WithDefault("24h")
        fun actionWindow(): Duration
    }

    /** Alertmanager base URL, read periodically for active alerts the responder does not know yet. */
    fun alertmanagerUrl(): Optional<String>

    interface Kagent {
        /** A2A endpoint of the diagnosis agent. */
        fun url(): String

        fun timeout(): Duration
    }

    interface GitHub {
        @WithDefault("true")
        fun enabled(): Boolean

        fun repository(): String

        /** Issues: read and write. Empty: issues are only logged. */
        fun token(): Optional<String>
    }
}
