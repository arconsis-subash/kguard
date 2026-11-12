package io.kguard

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

/** All `kubeguard.*` settings; defaults are in application.properties. */
@ConfigMapping(prefix = "kubeguard")
interface KubeGuardConfig {

    /** How often all rules are evaluated (also used by the Scheduler directly). */
    fun evaluationInterval(): Duration

    /** If set, only spans from these `service.name`s are evaluated. */
    fun services(): Optional<List<String>>

    fun ingest(): Ingest

    fun alertmanager(): Alertmanager

    interface Ingest {
        /** Exception type/message of a failed call that means "dependency not reachable" (database calls, rule 3/4). */
        fun connectionErrorPattern(): String

        /** Exception types of a failed SERVER span that come from JDBC (connection acquisition failures, rule 5). */
        fun jdbcExceptionPattern(): String
    }

    fun rules(): Rules

    interface Alertmanager {
        @WithDefault("true")
        fun enabled(): Boolean

        /** How often a firing alert is re-sent to keep it active in Alertmanager. */
        fun resendInterval(): Duration

        /** `endsAt` horizon of a firing alert; it expires on its own if KubeGuard stops refreshing it. */
        fun alertTtl(): Duration

        /** Labels added to every alert, e.g. namespace or cluster. */
        fun staticLabels(): Map<String, String>

        fun generatorUrl(): Optional<String>
    }

    interface Rules {
        fun downstreamLatency(): DownstreamLatency

        fun dependencyConnectivity(): DependencyConnectivity

        fun serviceLatency(): ServiceLatency

        fun podCrashloop(): PodCrashloop

        fun podUnavailable(): PodUnavailable

        fun memoryExhaustion(): MemoryExhaustion

        fun podPending(): PodStuck

        fun podTerminating(): PodStuck
    }

    interface Rule {
        @WithDefault("true")
        fun enabled(): Boolean

        fun window(): Duration

        fun minSamples(): Int

        fun firingDuration(): Duration

        fun recoveryDuration(): Duration

        fun severity(): String
    }

    interface DownstreamLatency : Rule {
        fun p95Threshold(): Duration
    }

    interface PodCrashloop : Rule {
        /** Restarts of one container within the window that count as a crash loop. */
        fun minRestarts(): Long

        /** A container whose last report is older than this is gone (deleted, replaced) and no longer counts. */
        fun staleAfter(): Duration
    }

    interface PodUnavailable : Rule {
        /** A container whose last report is older than this is gone (deleted, replaced) and no longer counts. */
        fun staleAfter(): Duration
    }

    interface MemoryExhaustion : Rule {
        /** The heap floor is the lowest value in each bucket of this length (what stays after garbage collection). */
        fun bucket(): Duration

        /** Floors needed per pod before it is forecast. */
        fun minBuckets(): Int

        /** A pod counts only from this share of its maximum heap (0..1). */
        fun minFraction(): Double

        /** Met when a pod will be full within this time. */
        fun predictWithin(): Duration

        /** A pod whose last report is older than this is gone and no longer counts. */
        fun staleAfter(): Duration
    }

    interface PodStuck : Rule {
        /** How long a pod may be Pending (since creation) or Terminating (past its grace period) before it counts. */
        fun stuckAfter(): Duration

        /** A pod whose last report is older than this is gone and no longer counts. */
        fun staleAfter(): Duration
    }

    interface ServiceLatency : Rule {
        fun p95Threshold(): Duration
    }

    interface DependencyConnectivity : Rule {
        /** Share of calls (0..1) that must fail without a response. */
        fun failureRatioThreshold(): Double
    }
}
