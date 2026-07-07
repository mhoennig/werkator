package de.hoennig.gittally.server

import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.config.GiteaConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Links into the Gitea web UI, like legacy `gitea_branch_web_url`; null when Gitea is not configured. */
class GiteaWebLinks(
    gitea: GiteaConfig,
) {
    val repoUrl: String? =
        listOf(gitea.baseUrl.trim().trimEnd('/'), gitea.owner.trim(), gitea.repo.trim())
            .takeIf { parts -> parts.all { it.isNotEmpty() } }
            ?.let { (baseUrl, owner, repo) -> "$baseUrl/${escapePath(owner)}/${escapePath(repo)}" }

    fun branchUrl(branch: String): String? = repoUrl?.let { "$it/src/branch/${escapePath(branch)}" }

    fun commitUrl(commit: String): String? = repoUrl?.let { "$it/commit/${escapePath(commit)}" }

    /** Escapes each path segment but keeps `/` — branch names may contain slashes. */
    private fun escapePath(value: String): String =
        value
            .split('/')
            .joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
}

/** Display formatting shared by the server-rendered views; `gittally.js` renders the same formats. */
object UiFormats {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun timestamp(instant: Instant): String = timestampFormat.format(instant)

    /** `m:ss`, or `h:mm:ss` from one hour — like the legacy `MM:SS` duration column. */
    fun duration(duration: Duration?): String {
        if (duration == null || duration.isNegative) {
            return ""
        }
        val seconds = duration.seconds
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, rest)
        } else {
            "%d:%02d".format(minutes, rest)
        }
    }
}

/** One row of the latest/history build tables. */
data class BuildRowView(
    val branch: String,
    val commit: String,
    val commitAbbrev: String,
    val status: String,
    val startedAtIso: String,
    val startedAt: String,
    val duration: String,
    val artifactKey: String,
    val branchUrl: String?,
    val commitUrl: String?,
) {
    companion object {
        fun from(
            result: BuildResult,
            links: GiteaWebLinks,
        ) = BuildRowView(
            branch = result.branch,
            commit = result.commit,
            commitAbbrev = result.commit.take(12),
            status = result.status.jsonName,
            startedAtIso = result.startedAt.toString(),
            startedAt = UiFormats.timestamp(result.startedAt),
            duration = UiFormats.duration(result.duration),
            artifactKey = result.artifactKey,
            branchUrl = links.branchUrl(result.branch),
            commitUrl = links.commitUrl(result.commit),
        )
    }
}

/** One card of the current-builds view; the live log is fetched by `gittally.js`. */
data class CurrentBuildView(
    val branch: String,
    val commit: String,
    val commitAbbrev: String,
    val status: String,
    val startedAtIso: String,
    val startedAt: String,
    val artifactKey: String,
    val branchUrl: String?,
    val commitUrl: String?,
)
