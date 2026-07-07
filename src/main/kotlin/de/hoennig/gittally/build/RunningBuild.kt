package de.hoennig.gittally.build

import java.nio.file.Path
import java.time.Instant

/** Handle to a build accepted by the [BuildExecutor]; log paths become valid once the build runs. */
data class RunningBuild(
    val branch: String,
    val commit: String,
    val artifactKey: String,
    val startedAt: Instant,
    /** Working directory for build output; handed to the [ArtifactStore] when the build ends. */
    val stagingDir: Path,
    /** Combined stdout+stderr log, written live while the build runs. */
    val liveLogFile: Path,
)

/** Published via Spring's `ApplicationEventPublisher` on every persisted status transition. */
data class BuildStatusChangedEvent(
    val result: BuildResult,
)
