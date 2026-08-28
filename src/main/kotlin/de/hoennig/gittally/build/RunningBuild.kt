package de.hoennig.gittally.build

import java.nio.file.Path
import java.time.Instant

/** Handle to a build accepted by the [BuildExecutor]; log paths become valid once the build runs. */
data class RunningBuild(
    /** The git branch being built. */
    val branch: String,
    /** The build name the result is recorded under; the branch name unless a named auto-build slot set its own. */
    val name: String = branch,
    val commit: String,
    val artifactKey: String,
    val startedAt: Instant,
    /** Working directory for build output; handed to the [ArtifactStore] when the build ends. */
    val stagingDir: Path,
    /** Combined stdout+stderr log, written live while the build runs. */
    val liveLogFile: Path,
    /** Command dictated by the triggering auto-build slot; null runs the branch's configured `buildCommand`. */
    val buildCommandOverride: String? = null,
) {
    /** Set by the executor when the build leaves the queue and starts executing. */
    @Volatile
    var runningSince: Instant? = null
}

/** Published via Spring's `ApplicationEventPublisher` on every persisted status transition. */
data class BuildStatusChangedEvent(
    val result: BuildResult,
)
