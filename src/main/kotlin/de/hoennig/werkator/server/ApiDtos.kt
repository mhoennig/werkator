package de.hoennig.werkator.server

import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildStatus
import java.time.Instant

/** JSON statuses are lowercase like the legacy TSV/HTML statuses. */
val BuildStatus.jsonName: String
    get() = name.lowercase()

data class BuildResultDto(
    /** The git branch that was built; the UI links this to Gitea. */
    val branch: String,
    /** The build name the result is recorded under; the UI displays and restarts by this (= [branch] unless a named auto-build slot). */
    val name: String = branch,
    val commit: String,
    val status: String,
    val startedAt: Instant,
    /** When the build left the queue; the UI derives the live build time from this, the wait time from [startedAt]. */
    val runningSince: Instant? = null,
    val durationSeconds: Long?,
    val artifactKey: String,
    /** The permanent branch URL, set only on the build it resolves to — the name's latest green build. */
    val latestGreenUrl: String? = null,
) {
    companion object {
        fun from(
            result: BuildResult,
            isLatestGreen: Boolean = false,
            base: String = "",
        ) = BuildResultDto(
            branch = result.branch,
            name = result.name,
            commit = result.commit,
            status = result.status.jsonName,
            startedAt = result.startedAt,
            runningSince = result.runningSince,
            durationSeconds = result.duration?.seconds,
            artifactKey = result.artifactKey,
            latestGreenUrl = if (isLatestGreen) BranchPermalinks.permanentUrl(result.name, base) else null,
        )
    }
}

/**
 * One entry of `GET /api/branches`, like a legacy branches-view row: an origin
 * branch with its latest build, or an `unknown` placeholder when never built.
 * [latestGreenUrl] is the permanent artifact URL of the branch's latest green
 * build; set only when this row's build is that green build, so the link appears
 * where it resolves to.
 */
data class BranchDto(
    /** The git branch; the UI links this to Gitea. */
    val branch: String,
    /** The row's build name; = [branch], except for the extra rows of named auto-build slots. */
    val name: String = branch,
    val commit: String,
    val status: String,
    val startedAt: Instant?,
    val runningSince: Instant? = null,
    val durationSeconds: Long?,
    val artifactKey: String,
    val latestGreenUrl: String? = null,
) {
    companion object {
        fun from(
            branch: String,
            headCommit: String,
            latest: BuildResult?,
            isLatestGreen: Boolean = false,
            name: String = branch,
        ) = if (latest == null) {
            BranchDto(
                branch = branch,
                name = name,
                commit = headCommit,
                status = CommitStatusDto.UNKNOWN_STATUS,
                startedAt = null,
                durationSeconds = null,
                artifactKey = "",
            )
        } else {
            BranchDto(
                branch = branch,
                name = name,
                commit = latest.commit,
                status = latest.status.jsonName,
                startedAt = latest.startedAt,
                runningSince = latest.runningSince,
                durationSeconds = latest.duration?.seconds,
                artifactKey = latest.artifactKey,
                latestGreenUrl = if (isLatestGreen) BranchPermalinks.permanentUrl(name) else null,
            )
        }
    }
}

/** One entry of `GET /api/builds/current`; the log grows while the build runs. */
data class CurrentBuildDto(
    val branch: String,
    /** The build name; = [branch] unless a named auto-build slot triggered this build. */
    val name: String = branch,
    val commit: String,
    val artifactKey: String,
    val status: String,
    val startedAt: Instant,
    val runningSince: Instant? = null,
    val logSize: Long,
)

/** Incremental live-log chunk; poll again with `offset = nextOffset`. */
data class LogTailDto(
    val artifactKey: String,
    val offset: Long,
    val nextOffset: Long,
    val content: String,
)

/**
 * Effective status of a commit: the Gitea status when available, otherwise the
 * local repository status, otherwise `unknown`. A Gitea failure is an explicit
 * [giteaError] value — the request answers HTTP 200 and never hangs.
 */
data class CommitStatusDto(
    val commit: String,
    val status: String,
    val localStatus: String?,
    val giteaStatus: String?,
    val giteaError: String?,
) {
    companion object {
        const val UNKNOWN_STATUS = "unknown"
    }
}
