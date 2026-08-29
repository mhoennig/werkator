package de.hoennig.gittally.config

import java.time.Duration
import java.time.Instant

/**
 * A named build (job) over the branches — ADR 0007. In YAML these live in the
 * top-level `builds` section next to the reserved execution key `maxConcurrent`
 * (split apart by [ConfigLoader]).
 *
 * A definition carries the complete description of one build. The `default` entry is
 * additionally the base every other definition inherits its settings from — but never
 * its trigger, see [SELECTOR_KEYS][ConfigLoader]. Unset values fall through to the
 * [BranchConfig] defaults.
 *
 * The `default` build records its results under the plain branch name; every other
 * build records under `<branch>@<name>` with its own history, retention pool, and
 * permanent latest-green link.
 */
data class BuildDefinition(
    /** Build every new commit of the selected branches. */
    val onPush: Boolean = false,
    /**
     * Daily UTC times `HH:MM`; each slot rebuilds the selected branches' heads once per day.
     * `??:MM` is the hourly form — it stands for that minute of every hour, so each of its
     * 24 slots triggers separately.
     */
    val atTimes: List<String> = emptyList(),
    /**
     * Branch names or glob patterns (`*` matches any characters, also across `/`);
     * empty selects all origin branches.
     */
    val branches: List<String> = emptyList(),
    /**
     * Only branches whose origin head commit is younger than this (e.g. `24h`);
     * empty applies no age filter. Combines with [branches] as an intersection.
     */
    val activeWithin: String = "",
    /** Overrides the branch's build command; null inherits it. */
    val buildCommand: String? = null,
    /** Overrides the branch's clean command; null inherits it. */
    val cleanCommand: String? = null,
    /** Overrides the branch's artifact directories; null inherits them. */
    val artifactDirs: List<String>? = null,
    /** Overrides the branch's stdout log file name; null inherits it. */
    val stdoutLog: String? = null,
    /** Overrides the branch's stderr log file name; null inherits it. */
    val stderrLog: String? = null,
    /**
     * The watcher builds a selected branch only while its head commit matches a
     * pull-request head; null inherits. Pinned — a branch's own committed config can
     * never set it, or it would bypass its own gate.
     */
    val requirePullRequest: Boolean? = null,
    /** Overrides of the branch's docker settings; null inherits them. */
    val docker: DockerOverrides? = null,
) {
    /** True when [branch] matches the [branches] patterns (or none are configured). */
    fun selectsByName(branch: String): Boolean = branches.isEmpty() || branches.any { globToRegex(it).matches(branch) }

    /**
     * True when [branch] passes both selector parts; [headCommittedAt] is the branch
     * head's committer time, only consulted while [activeWithin] is set (null then
     * deselects the branch).
     */
    fun selects(
        branch: String,
        headCommittedAt: () -> Instant?,
        now: Instant,
    ): Boolean {
        if (!selectsByName(branch)) {
            return false
        }
        if (activeWithin.isBlank()) {
            return true
        }
        val committedAt = headCommittedAt() ?: return false
        return committedAt >= now.minus(maxAge())
    }

    fun maxAge(): Duration = DurationParser.parse(activeWithin)

    /** The branch settings with this build's overrides applied; unset values fall through. */
    fun applyTo(branchConfig: BranchConfig): BranchConfig =
        branchConfig.copy(
            buildCommand = buildCommand ?: branchConfig.buildCommand,
            cleanCommand = cleanCommand ?: branchConfig.cleanCommand,
            artifactDirs = artifactDirs ?: branchConfig.artifactDirs,
            stdoutLog = stdoutLog ?: branchConfig.stdoutLog,
            stderrLog = stderrLog ?: branchConfig.stderrLog,
            requirePullRequest = requirePullRequest ?: branchConfig.requirePullRequest,
            docker =
                branchConfig.docker.copy(
                    enabled = docker?.enabled ?: branchConfig.docker.enabled,
                    image = docker?.image ?: branchConfig.docker.image,
                    dockerfile = docker?.dockerfile ?: branchConfig.docker.dockerfile,
                    context = docker?.context ?: branchConfig.docker.context,
                    network = docker?.network ?: branchConfig.docker.network,
                    env = docker?.env ?: branchConfig.docker.env,
                ),
        )

    companion object {
        /** Name of the implicit build that preserves the pre-ADR-0007 behavior: `onPush` over all branches. */
        const val DEFAULT = "default"

        /** The result-pool name of [build] on [branch]: the plain branch for the default build. */
        fun poolName(
            branch: String,
            build: String,
        ): String = if (build == DEFAULT) branch else "$branch@$build"

        private fun globToRegex(pattern: String): Regex =
            Regex(
                pattern
                    .split('*')
                    .joinToString(".*") { Regex.escape(it) },
            )
    }
}

/** Nullable docker overrides of a [BuildDefinition]; null values inherit the branch's setting. */
data class DockerOverrides(
    /** Run the build in a container instead of natively. Pinned — a branch must not escape its sandbox. */
    val enabled: Boolean? = null,
    /** Docker network mode. Pinned — a branch must not change the sandbox's reachability. */
    val network: String? = null,
    val image: String? = null,
    val dockerfile: String? = null,
    val context: String? = null,
    val env: Map<String, String>? = null,
)
