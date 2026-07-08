package de.hoennig.gittally.server

import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildStatus
import java.time.Instant

/** JSON statuses are lowercase like the legacy TSV/HTML statuses. */
val BuildStatus.jsonName: String
    get() = name.lowercase()

data class BuildResultDto(
    val branch: String,
    val commit: String,
    val status: String,
    val startedAt: Instant,
    val durationSeconds: Long?,
    val artifactKey: String,
) {
    companion object {
        fun from(result: BuildResult) =
            BuildResultDto(
                branch = result.branch,
                commit = result.commit,
                status = result.status.jsonName,
                startedAt = result.startedAt,
                durationSeconds = result.duration?.seconds,
                artifactKey = result.artifactKey,
            )
    }
}

/**
 * One entry of `GET /api/branches`, like a legacy branches-view row: an origin
 * branch with its latest build, or an `unknown` placeholder when never built.
 * [latestGreenUrl] is the permanent artifact URL of the branch's latest green
 * build; null while the branch has never built successfully.
 */
data class BranchDto(
    val branch: String,
    val commit: String,
    val status: String,
    val startedAt: Instant?,
    val durationSeconds: Long?,
    val artifactKey: String,
    val latestGreenUrl: String? = null,
) {
    companion object {
        fun from(
            branch: String,
            headCommit: String,
            latest: BuildResult?,
            hasGreenBuild: Boolean = false,
        ) = if (latest == null) {
            BranchDto(
                branch = branch,
                commit = headCommit,
                status = CommitStatusDto.UNKNOWN_STATUS,
                startedAt = null,
                durationSeconds = null,
                artifactKey = "",
            )
        } else {
            BranchDto(
                branch = branch,
                commit = latest.commit,
                status = latest.status.jsonName,
                startedAt = latest.startedAt,
                durationSeconds = latest.duration?.seconds,
                artifactKey = latest.artifactKey,
                latestGreenUrl = if (hasGreenBuild) BranchPermalinks.permanentUrl(branch) else null,
            )
        }
    }
}

/** One entry of `GET /api/builds/current`; the log grows while the build runs. */
data class CurrentBuildDto(
    val branch: String,
    val commit: String,
    val artifactKey: String,
    val status: String,
    val startedAt: Instant,
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
