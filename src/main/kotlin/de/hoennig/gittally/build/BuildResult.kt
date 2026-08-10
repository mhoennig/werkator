package de.hoennig.gittally.build

import java.time.Duration
import java.time.Instant

data class BuildResult(
    val branch: String,
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
