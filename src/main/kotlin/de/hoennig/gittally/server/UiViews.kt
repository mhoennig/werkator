package de.hoennig.gittally.server

import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.config.GiteaConfig
import de.hoennig.gittally.metrics.MetricAggregate
import de.hoennig.gittally.metrics.SystemMetrics
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    private val timeOfDayFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

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

    /** Two-decimal metric value with a dot like JS `toFixed(2)`; `n/a` when the source is unavailable. */
    fun metric(value: Double?): String =
        if (value == null || !value.isFinite()) {
            "n/a"
        } else {
            String.format(Locale.ROOT, "%.2f", value)
        }

    fun timeOfDay(instant: Instant): String = timeOfDayFormat.format(instant)

    /**
     * CSS class highlighting a critical utilization: `metric-warn` from 80% of [total],
     * `metric-crit` from 90%, empty below or when either value is unavailable.
     * `gittally.js` (`utilizationClass`) must apply the same thresholds.
     */
    fun utilizationClass(
        used: Double?,
        total: Double?,
    ): String {
        if (used == null || total == null || !used.isFinite() || !total.isFinite() || total <= 0) {
            return ""
        }
        val ratio = used / total
        return when {
            ratio >= UTILIZATION_CRIT -> "metric-crit"
            ratio >= UTILIZATION_WARN -> "metric-warn"
            else -> ""
        }
    }

    private const val UTILIZATION_WARN = 0.80
    private const val UTILIZATION_CRIT = 0.90
}

/** One row of the build tables; [latestGreenUrl] only on the build that permanent link resolves to. */
data class BuildRowView(
    /** The git branch — target of the Gitea link and the copy button. */
    val branch: String,
    /** The displayed build name; = [branch] unless a named auto-build slot recorded the build. */
    val name: String,
    val commit: String,
    val commitAbbrev: String,
    val status: String,
    val startedAtIso: String,
    val startedAt: String,
    /** ISO timestamp of the run start for the live build-time ticker; empty while queued. */
    val runningSinceIso: String,
    val duration: String,
    val artifactKey: String,
    val branchUrl: String?,
    val commitUrl: String?,
    val latestGreenUrl: String? = null,
    /** True while the build has no artifacts yet — the artifact link then shows the log-only icon. */
    val inProgress: Boolean = false,
) {
    companion object {
        fun from(
            result: BuildResult,
            links: GiteaWebLinks,
            latestGreenUrl: String? = null,
        ) = BuildRowView(
            branch = result.branch,
            name = result.name,
            commit = result.commit,
            commitAbbrev = result.commit.take(12),
            status = result.status.jsonName,
            startedAtIso = result.startedAt.toString(),
            startedAt = UiFormats.timestamp(result.startedAt),
            runningSinceIso = result.runningSince?.toString() ?: "",
            duration = UiFormats.duration(result.duration),
            artifactKey = result.artifactKey,
            branchUrl = links.branchUrl(result.branch),
            commitUrl = links.commitUrl(result.commit),
            latestGreenUrl = latestGreenUrl,
            inProgress = !result.status.isTerminal,
        )

        /** A branches-view row; never-built branches have no timestamps and no artifact. */
        fun from(
            entry: BranchDto,
            links: GiteaWebLinks,
        ) = BuildRowView(
            branch = entry.branch,
            name = entry.name,
            commit = entry.commit,
            commitAbbrev = entry.commit.take(12),
            status = entry.status,
            startedAtIso = entry.startedAt?.toString() ?: "",
            startedAt = entry.startedAt?.let { UiFormats.timestamp(it) } ?: "",
            runningSinceIso = entry.runningSince?.toString() ?: "",
            duration = UiFormats.duration(entry.durationSeconds?.let { Duration.ofSeconds(it) }),
            artifactKey = entry.artifactKey,
            branchUrl = links.branchUrl(entry.branch),
            commitUrl = links.commitUrl(entry.commit),
            latestGreenUrl = entry.latestGreenUrl,
            inProgress = entry.status == "running" || entry.status == "pending",
        )
    }
}

/**
 * One report link of the artifact index. [failures] is the failures counter of a
 * Gradle-style HTML report index page; null when the page declares none (e.g. Jacoco),
 * so only real test reports get a failed-badge.
 */
data class ReportIndexView(
    val path: String,
    val failures: Int?,
)

/**
 * One log link of the artifact index. [failed] marks the logs that actually carry the
 * build tool's failure line, so a red build points at the log worth opening; it stays
 * false for successful builds, whose logs are not scanned at all.
 */
data class LogFileView(
    val name: String,
    val failed: Boolean,
)

/** One card of the current-builds view; the live log is fetched by `gittally.js`. */
data class CurrentBuildView(
    val branch: String,
    /** The displayed build name; = [branch] unless a named auto-build slot triggered the build. */
    val name: String,
    val commit: String,
    val commitAbbrev: String,
    val status: String,
    val startedAtIso: String,
    val startedAt: String,
    val runningSinceIso: String,
    val artifactKey: String,
    val branchUrl: String?,
    val commitUrl: String?,
)

/**
 * One row of the system-metrics table. The [key] matches the JSON field of
 * `GET /api/system`, so `gittally.js` can update the cells in place.
 */
data class MetricRowView(
    val key: String,
    val label: String,
    val current: String,
    val min: String,
    val max: String,
    val avg: String,
    /** `metric-warn`/`metric-crit` when the current value is a critical share of its total. */
    val currentClass: String = "",
) {
    companion object {
        fun from(
            key: String,
            label: String,
            aggregate: MetricAggregate?,
            total: Double? = null,
        ) = MetricRowView(
            key = key,
            label = label,
            current = UiFormats.metric(aggregate?.current),
            min = UiFormats.metric(aggregate?.min),
            max = UiFormats.metric(aggregate?.max),
            avg = UiFormats.metric(aggregate?.avg),
            currentClass = UiFormats.utilizationClass(aggregate?.current, total),
        )
    }
}

/** The system view: the metric rows plus the totals/updated info line, like the legacy system page. */
data class SystemMetricsView(
    val rows: List<MetricRowView>,
    val cpuCount: String,
    val ramTotal: String,
    val diskTotal: String,
    val updated: String,
) {
    companion object {
        fun from(metrics: SystemMetrics) =
            SystemMetricsView(
                rows =
                    listOf(
                        MetricRowView.from("cpuUsed", "CPU used (cores)", metrics.cpuUsed, metrics.cpuCount.toDouble()),
                        MetricRowView.from("cpuIdle", "CPU idle (cores)", metrics.cpuIdle),
                        MetricRowView.from("ramUsedGib", "RAM used (GiB)", metrics.ramUsedGib, metrics.ramTotalGib),
                        MetricRowView.from("ramFreeGib", "RAM free (GiB)", metrics.ramFreeGib),
                        MetricRowView.from("diskUsedGib", "Disk used (GiB)", metrics.diskUsedGib, metrics.diskTotalGib),
                        MetricRowView.from("diskFreeGib", "Disk free (GiB)", metrics.diskFreeGib),
                        MetricRowView.from("repoSizeGib", "Repo size (GiB)", metrics.repoSizeGib),
                    ),
                cpuCount = "${metrics.cpuCount} cores",
                ramTotal = metrics.ramTotalGib?.let { "${UiFormats.metric(it)} GiB" } ?: "n/a",
                diskTotal = metrics.diskTotalGib?.let { "${UiFormats.metric(it)} GiB" } ?: "n/a",
                updated = metrics.timestamp?.let { UiFormats.timeOfDay(it) } ?: "n/a",
            )
    }
}
