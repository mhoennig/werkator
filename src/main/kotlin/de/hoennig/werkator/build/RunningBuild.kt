package de.hoennig.werkator.build

import de.hoennig.werkator.config.BuildDefinition
import de.hoennig.werkator.repo.RepoContext
import java.nio.file.Path
import java.time.Instant

/** Handle to a build accepted by the [BuildExecutor]; log paths become valid once the build runs. */
data class RunningBuild(
    /**
     * The repository this build belongs to; the context object is the identity
     * (ADR 0009), so it compares by reference. Without it neither the current-builds
     * view nor the watcher's worktree pruning could tell two repositories apart —
     * both would see every repository's running builds as their own.
     */
    val repo: RepoContext,
    /** The git branch being built. */
    val branch: String,
    /** The build definition (job) this build runs; its settings are resolved from config at run time. */
    val build: String = BuildDefinition.DEFAULT,
    /** The pool the result is recorded under: the branch, or `<branch>@<build>` for non-default builds. */
    val name: String = BuildDefinition.poolName(branch, build),
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
