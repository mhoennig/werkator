package de.hoennig.gittally.gitea

import de.hoennig.gittally.build.BuildStatus

/** Gitea commit-status state published for this build status. */
fun BuildStatus.toGiteaState(): String =
    when (this) {
        BuildStatus.SUCCESS -> "success"
        BuildStatus.FAILED, BuildStatus.INTERRUPTED, BuildStatus.CANCELLED -> "failure"
        BuildStatus.PENDING, BuildStatus.RUNNING -> "pending"
    }

/**
 * Build status for a Gitea commit-status state, or null for unknown states.
 * The reverse mapping is lossy: Gitea only distinguishes success/failure/pending.
 */
fun buildStatusFromGiteaState(state: String): BuildStatus? =
    when (state) {
        "success" -> BuildStatus.SUCCESS
        "failure", "error", "warning" -> BuildStatus.FAILED
        "pending" -> BuildStatus.RUNNING
        else -> null
    }
