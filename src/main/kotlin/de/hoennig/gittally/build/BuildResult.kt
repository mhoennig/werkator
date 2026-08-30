package de.hoennig.werkator.build

import de.hoennig.werkator.config.BuildDefinition
import java.time.Duration
import java.time.Instant

data class BuildResult(
    /** The git branch that was built — Gitea links and origin lookups always use this. */
    val branch: String,
    /**
     * The build definition (job) this result belongs to; re-runs (restart, retry,
     * startup recovery) resolve their settings from the current configuration by
     * this name — the job definition is the source of truth, not the recorded run.
     */
    val build: String = BuildDefinition.DEFAULT,
    /**
     * The pool this result is recorded under: the branch name for the default build,
     * `<branch>@<build>` otherwise (see [BuildDefinition.poolName]). History rows,
     * retention pools, latest status, and the permanent latest-green artifact links
     * are all keyed by this name.
     */
    val name: String = BuildDefinition.poolName(branch, build),
    val commit: String,
    val status: BuildStatus,
    /** When the build was accepted (enqueued); the time until [runningSince] is queue wait. */
    val startedAt: Instant,
    /** When the build actually started executing; null while queued or when cancelled in the queue. */
    val runningSince: Instant? = null,
    /** Pure build execution time (from [runningSince]), without the queue wait. */
    val duration: Duration? = null,
    val artifactKey: String,
)
