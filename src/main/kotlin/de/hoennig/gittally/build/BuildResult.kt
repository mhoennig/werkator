package de.hoennig.gittally.build

import java.time.Duration
import java.time.Instant

data class BuildResult(
    /** The git branch that was built — Gitea links and origin lookups always use this. */
    val branch: String,
    /**
     * The build name this result is recorded under: the branch name, unless a named
     * auto-build slot (`autoBuild.times[].name`, e.g. `master@nightly`) set its own.
     * History rows, retention pools, latest status, and the permanent latest-green
     * artifact links are all keyed by this name.
     */
    val name: String = branch,
    val commit: String,
    val status: BuildStatus,
    /** When the build was accepted (enqueued); the time until [runningSince] is queue wait. */
    val startedAt: Instant,
    /** When the build actually started executing; null while queued or when cancelled in the queue. */
    val runningSince: Instant? = null,
    /** Pure build execution time (from [runningSince]), without the queue wait. */
    val duration: Duration? = null,
    /**
     * Command dictated by the auto-build slot that triggered this build; null means the
     * branch's configured `buildCommand` was used. A restart or startup-recovery re-run
     * repeats the build with this command, so a build always reruns what it originally ran.
     */
    val buildCommandOverride: String? = null,
    val artifactKey: String,
)
