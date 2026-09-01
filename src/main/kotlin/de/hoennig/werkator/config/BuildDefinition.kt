package de.hoennig.werkator.config

import java.time.Duration
import java.time.Instant

/**
 * A named build (job) over the branches — ADR 0007. In YAML these live in the
 * top-level `builds` section next to the reserved execution key `maxConcurrent`
 * (split apart by [ConfigLoader]).
 *
 * A definition describes one build completely, in two halves: [trigger] says when it
 * runs and for which branches, everything else says what it does. The `default` entry
 * is additionally the base every other definition inherits from — its settings, never
 * its [trigger]. That is why the halves are separated structurally instead of by a list
 * of key names: a selector added to [TriggerConfig] later is non-inheritable by
 * construction, not because someone remembered to extend a list.
 */
data class BuildDefinition(
    /** When and for which branches this build runs; never inherited from `builds.default`. */
    val trigger: TriggerConfig = TriggerConfig(),
    /** Overrides the build command; null inherits it. */
    val buildCommand: String? = null,
    /** Overrides the clean command; null inherits it. */
    val cleanCommand: String? = null,
    /** Overrides the artifact directories; null inherits them. */
    val artifactDirs: List<String>? = null,
    /** Overrides the stdout log file name; null inherits it. */
    val stdoutLog: String? = null,
    /** Overrides the stderr log file name; null inherits it. */
    val stderrLog: String? = null,
    /**
     * The watcher builds a selected branch only while its head commit matches a
     * pull-request head; null inherits. Pinned — a branch's own committed config can
     * never set it, or it would bypass its own gate.
     */
    val requirePullRequest: Boolean? = null,
    /**
     * Gitea commit status context of this build; null uses `gitea.statusContext`.
     * Two builds of the same commit under the same context overwrite each other's
     * result, so a second build over a branch needs its own context to be readable.
     */
    val statusContext: String? = null,
    /** Overrides of the docker settings; null inherits them. */
    val docker: DockerOverrides? = null,
    /** Overrides of the bwrap settings; null inherits them. */
    val bwrap: BwrapOverrides? = null,
) {
    /** The settings this build runs with: [branchConfig] with this definition applied; unset values fall through. */
    fun applyTo(branchConfig: BranchConfig): BranchConfig =
        branchConfig.copy(
            buildCommand = buildCommand ?: branchConfig.buildCommand,
            cleanCommand = cleanCommand ?: branchConfig.cleanCommand,
            artifactDirs = artifactDirs ?: branchConfig.artifactDirs,
            stdoutLog = stdoutLog ?: branchConfig.stdoutLog,
            stderrLog = stderrLog ?: branchConfig.stderrLog,
            requirePullRequest = requirePullRequest ?: branchConfig.requirePullRequest,
            statusContext = statusContext ?: branchConfig.statusContext,
            docker =
                branchConfig.docker.copy(
                    enabled = docker?.enabled ?: branchConfig.docker.enabled,
                    image = docker?.image ?: branchConfig.docker.image,
                    dockerfile = docker?.dockerfile ?: branchConfig.docker.dockerfile,
                    context = docker?.context ?: branchConfig.docker.context,
                    network = docker?.network ?: branchConfig.docker.network,
                    env = docker?.env ?: branchConfig.docker.env,
                ),
            bwrap =
                branchConfig.bwrap.copy(
                    enabled = bwrap?.enabled ?: branchConfig.bwrap.enabled,
                    rootfs = bwrap?.rootfs ?: branchConfig.bwrap.rootfs,
                    werkdock = bwrap?.werkdock ?: branchConfig.bwrap.werkdock,
                    env = bwrap?.env ?: branchConfig.bwrap.env,
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
    }
}

/**
 * When a build runs and for which branches — the `trigger` block of a build definition,
 * and the one part of it that is never inherited from `builds.default`.
 *
 * A definition with neither [onPush] nor [atTimes] never triggers automatically; that is
 * how `builds.default` is written when it is meant as a settings base only.
 */
data class TriggerConfig(
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
     * empty selects all origin branches. A pattern prefixed with `!` excludes instead,
     * and an exclusion always wins — `["*", "!master"]` is every branch but master.
     */
    val branches: List<String> = emptyList(),
    /**
     * Only branches whose origin head commit is younger than this (e.g. `24h`);
     * empty applies no age filter. Combines with [branches] as an intersection.
     */
    val activeWithin: String = "",
) {
    /** True when [branch] matches the [branches] patterns (or none are configured) and none excludes it. */
    fun selectsByName(branch: String): Boolean {
        val (excluding, including) = branches.partition { it.startsWith(EXCLUDE_PREFIX) }
        if (excluding.any { globToRegex(it.removePrefix(EXCLUDE_PREFIX)).matches(branch) }) {
            return false
        }
        return including.isEmpty() || including.any { globToRegex(it).matches(branch) }
    }

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

    companion object {
        /** Marks a [branches] pattern as excluding. */
        const val EXCLUDE_PREFIX = "!"

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

/** Nullable bubblewrap overrides of a [BuildDefinition]; null values inherit the branch's setting. */
data class BwrapOverrides(
    /** Run the build in the bwrap sandbox instead of natively. Pinned — a branch must not escape its sandbox. */
    val enabled: Boolean? = null,
    /** Rootfs archive source. Pinned — a branch must not substitute a foreign rootfs. */
    val rootfs: String? = null,
    /** The werkdock CLI executing the sandbox. Pinned — a branch must not substitute the executing binary. */
    val werkdock: String? = null,
    val env: Map<String, String>? = null,
)
