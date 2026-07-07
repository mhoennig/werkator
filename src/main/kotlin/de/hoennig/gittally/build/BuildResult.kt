package de.hoennig.gittally.build

import java.time.Duration
import java.time.Instant

data class BuildResult(
    val branch: String,
    val commit: String,
    val status: BuildStatus,
    val startedAt: Instant,
    val duration: Duration? = null,
    val artifactKey: String,
)
