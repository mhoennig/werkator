package de.hoennig.werkator.watcher

import de.hoennig.werkator.build.ArtifactKeys
import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.build.GitWorktreeWorkspaces
import de.hoennig.werkator.config.BuildDefinition
import de.hoennig.werkator.config.ConfigFiles
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.config.DurationParser
import de.hoennig.werkator.config.WerkatorConfig
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Replaces the legacy blocking main loop: a non-blocking fixed-delay poll cycle that,
 * for every served repository, fetches origin, enqueues due branches via the async
 * [BuildExecutor], and prunes retention — it never waits for a build and never builds
 * in the primary checkout (whose branch refs it does fast-forward, see
 * [fastForwardLocalRefs]). One repository's failure never reaches another: each is
 * polled in its own guard and reports on its own in [WatcherState.repositories].
 * The loop only runs after an explicit [start] (server/watch mode, step 07);
 * nothing is scheduled during CLI commands or tests.
 */
@Service
class Watcher(
    private val gitService: GitService,
    private val buildExecutor: BuildExecutor,
    private val configLoader: ConfigLoader,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Watcher::class.java)

    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var state = WatcherState()

    /** What the watcher remembers about a repository between polls, keyed by the context (identity). */
    private val watched = ConcurrentHashMap<RepoContext, RepoWatch>()

    fun state(): WatcherState = state

    private fun watchOf(repo: RepoContext): RepoWatch = watched.computeIfAbsent(repo) { RepoWatch() }

    /**
     * The per-repository poll memory: what was logged already, and the cached branch
     * definitions. Kept apart from the shared [WatcherState] so that the next session
     * can iterate contexts without one repository's outage silencing another's.
     */
    private class RepoWatch {
        /** The branches.*.autoBuild deprecation is logged once per repository, not once per poll. */
        @Volatile
        var warnedDeprecatedAutoBuild = false

        /**
         * The fetch failure last written to the log, so a lasting outage does not repeat the
         * same warning on every poll — one wrong token produced 297 identical lines before
         * this. Null while the last fetch succeeded, which is also what makes the recovery
         * loggable.
         */
        @Volatile
        var loggedFetchError: String? = null

        /** Build definitions per branch, cached by the branch's head commit — see [definitionsFor]. */
        val branchDefinitions = ConcurrentHashMap<String, CachedDefinitions>()
    }

    /**
     * Runs the startup recovery of every repository and schedules the poll loop with the
     * fixed delay `watcher.pollInterval` — one loop, one delay: the instance's setting,
     * which every repository's effective config carries; the first poll runs immediately.
     */
    @Synchronized
    fun start(repos: List<RepoContext>) {
        check(scheduler == null) { "watcher is already running" }
        require(repos.isNotEmpty()) { "no repository to watch" }
        repos.forEach { recoverSafely(it) }
        val interval = DurationParser.parse(configLoader.load(repos.first().workingDir).watcher.pollInterval)
        scheduler =
            Executors
                .newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "werkator-watcher").apply { isDaemon = true }
                }.also {
                    it.scheduleWithFixedDelay({ pollAll(repos) }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
                }
        state = state.copy(running = true)
    }

    /** A repository whose recovery crashes is still polled; the others' recovery is never skipped. */
    private fun recoverSafely(repo: RepoContext) {
        try {
            recoverOnStartup(repo)
        } catch (e: Exception) {
            log.error("[{}] startup recovery failed", repo.name, e)
        }
    }

    @Synchronized
    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        state = state.copy(running = false)
    }

    /**
     * Port of the legacy startup recovery: best-effort fetch, mark stale RUNNING and
     * superseded PENDING builds as INTERRUPTED, then re-enqueue every branch whose
     * latest build never finished and which still exists on origin.
     */
    fun recoverOnStartup(repo: RepoContext) {
        val workingDir = repo.workingDir
        val repository = repo.results
        try {
            gitService.fetchOrigin(workingDir)
        } catch (e: Exception) {
            log.warn("startup fetch failed; recovering from the last known origin state: {}", e.message)
        }
        repository.markStaleRunningAsInterrupted().forEach {
            log.info("marked stale build of branch {} as interrupted", it.branch)
        }
        val restartable =
            repository
                .latestPerName()
                .filter { it.status == BuildStatus.INTERRUPTED || it.status == BuildStatus.PENDING }
        for (result in restartable) {
            val commit = gitService.originHeadCommit(result.branch, workingDir)
            if (commit == null) {
                if (result.status == BuildStatus.PENDING) {
                    // a PENDING entry is prune-immune (it normally belongs to the executor);
                    // close this orphan out so the gone branch can be pruned
                    repository.updateByArtifactKey(result.artifactKey) { it.copy(status = BuildStatus.INTERRUPTED) }
                }
                log.info("not restarting build of branch {}: branch is gone from origin", result.branch)
                continue
            }
            if (result.status == BuildStatus.PENDING) {
                // the executor queue did not survive the restart; the re-enqueued build supersedes the stale entry
                repository.updateByArtifactKey(result.artifactKey) { it.copy(status = BuildStatus.INTERRUPTED) }
            }
            log.info("restarting unfinished build {} of branch {}", result.build, result.branch)
            // the re-run resolves its settings from the current config by the recorded build name
            buildExecutor.startBuild(repo, result.branch, commit, result.build)
        }
    }

    /** One poll cycle over a single repository; see [pollAll]. */
    fun poll(repo: RepoContext) = pollAll(listOf(repo))

    /**
     * One poll cycle over all served repositories, never blocking on a build. Each
     * repository is polled in its own guard — a crash or an unreachable origin is that
     * repository's report, and the next one is polled regardless — and the cycle's
     * [state] aggregates the reports: the top-level fields read as before with one
     * repository, and name the repository in front of every message with several.
     */
    fun pollAll(repos: List<RepoContext>) {
        val startedAt = clock.instant()
        val reports = repos.map { pollSafely(it, startedAt) }
        val several = repos.size > 1

        fun named(
            report: RepoWatcherState,
            message: String,
        ): String = if (several) "${report.name}: $message" else message
        state =
            state.copy(
                lastPollAt = startedAt,
                lastFetchError = reports.mapNotNull { report -> report.lastFetchError?.let { named(report, it) } }.joinOrNull(),
                lastPollError = reports.mapNotNull { report -> report.lastPollError?.let { named(report, it) } }.joinOrNull(),
                queuedBranches = reports.flatMap { it.queuedBranches },
                repositories = reports,
            )
    }

    private fun List<String>.joinOrNull(): String? = takeIf { it.isNotEmpty() }?.joinToString("; ")

    private fun pollSafely(
        repo: RepoContext,
        startedAt: Instant,
    ): RepoWatcherState =
        try {
            pollRepo(repo, startedAt)
        } catch (e: Exception) {
            log.error("[{}] poll cycle failed", repo.name, e)
            RepoWatcherState(repo.name, lastPollAt = startedAt, lastPollError = e.message ?: e.javaClass.simpleName)
        }

    /**
     * One repository's poll: fetch origin (on failure: log once per message, report it,
     * retry next cycle), enqueue due branches — changed local branches first, then recent
     * new origin branches, then due auto-build slots — then fast-forward the local branch
     * refs, and finally prune results, artifacts, and worktrees of branches gone from origin.
     */
    private fun pollRepo(
        repo: RepoContext,
        startedAt: Instant,
    ): RepoWatcherState {
        val workingDir = repo.workingDir
        val watch = watchOf(repo)
        try {
            gitService.fetchOrigin(workingDir)
            if (watch.loggedFetchError != null) {
                log.info("[{}] fetching origin succeeded again", repo.name)
                watch.loggedFetchError = null
            }
        } catch (e: Exception) {
            val failure = e.message ?: e.javaClass.simpleName
            if (watch.loggedFetchError != failure) {
                log.warn("[{}] fetching origin failed; retrying every cycle until it succeeds: {}", repo.name, failure)
                watch.loggedFetchError = failure
            }
            return RepoWatcherState(repo.name, lastPollAt = startedAt, lastFetchError = failure)
        }
        val config = configLoader.load(workingDir)
        val originBranches = gitService.originBranches(workingDir)
        enqueueDueBranches(repo, config, originBranches.toSet())
        if (config.watcher.fastForwardLocalRefs) {
            fastForwardLocalRefs(workingDir)
        }
        prune(repo, config, originBranches)
        return RepoWatcherState(
            repo.name,
            lastPollAt = startedAt,
            queuedBranches =
                repo.results
                    .latestPerName()
                    .filter { it.status == BuildStatus.PENDING || it.status == BuildStatus.RUNNING }
                    .map { it.name },
        )
    }

    /**
     * Brings the primary checkout's local branch refs up to origin, because a build worktree
     * shares that `.git`: a build tool comparing a local branch with its origin counterpart
     * would otherwise see a ref frozen at the state of the last checkout.
     *
     * Deliberately the last step before pruning — the enqueue decision above reads a
     * lagging local ref as "this branch has new commits", so syncing earlier in the cycle
     * would suppress the very build it prepares. Never fatal for the poll cycle.
     */
    private fun fastForwardLocalRefs(workingDir: Path) {
        try {
            val moved = gitService.fastForwardLocalBranches(workingDir)
            if (moved.isNotEmpty()) {
                log.info("fast-forwarded local branch refs to origin: {}", moved.joinToString(", "))
            }
        } catch (e: Exception) {
            log.warn("fast-forwarding local branch refs failed: {}", e.message)
        }
    }

    private fun enqueueDueBranches(
        repo: RepoContext,
        config: WerkatorConfig,
        originBranches: Set<String>,
    ) {
        val workingDir = repo.workingDir
        // one ls-remote per poll cycle at most, and only when a due branch requires a pull request
        val pullRequestHeads = lazy { gitService.pullRequestHeads(workingDir) }
        // one for-each-ref per cycle at most, and only when a definition filters by activeWithin
        val headCommitTimes = lazy { gitService.originBranchCommitTimes(workingDir) }
        val heads = gitService.originBranchHeads(workingDir)
        watchOf(repo).branchDefinitions.keys.retainAll(originBranches)
        val changedLocal =
            gitService
                .localBranches(workingDir)
                .filter { it in originBranches && gitService.hasNewCommits(it, workingDir) }
        val newOrigin =
            gitService.newOriginBranches(DurationParser.parse(config.watcher.newBranchMaxAge), workingDir)
        val changed = (changedLocal + newOrigin).distinct()
        for (branch in changed) {
            val onPush = definitionsFor(repo, branch, heads[branch], config).filterValues { it.trigger.onPush }
            for ((buildName, definition) in onPush) {
                if (selects(definition, branch, headCommitTimes)) {
                    startBuildIfDue(repo, branch, allowSameCommit = false, config, pullRequestHeads, buildName)
                }
            }
        }
        enqueueScheduledBuilds(repo, config, originBranches, heads, pullRequestHeads, headCommitTimes)
        enqueueDeprecatedAutoBuilds(repo, config, originBranches, pullRequestHeads)
    }

    /**
     * The build definitions that apply to [branch]: the primary configuration with the
     * branch's own committed `.werkator.yml` merged on top (the pinned keys stripped),
     * so a new `builds` configuration can be tried out on a branch without touching any
     * other branch's builds. A branch's definitions only ever apply to that branch —
     * their selectors are evaluated for it alone, so a definition committed on one branch
     * can never schedule builds of another.
     *
     * Cached per branch by its head commit *and* the primary configuration it was merged
     * with, so the `git show` runs only when the branch moved — but an edited machine or
     * project config takes effect on the next poll instead of waiting for a commit that
     * may never come. An unreadable branch config falls back to the primary definitions
     * instead of failing the poll cycle.
     */
    private fun definitionsFor(
        repo: RepoContext,
        branch: String,
        headCommit: String?,
        primary: WerkatorConfig,
    ): Map<String, BuildDefinition> {
        val workingDir = repo.workingDir
        val branchDefinitions = watchOf(repo).branchDefinitions
        val commit = headCommit ?: return primary.effectiveBuildDefinitions()
        branchDefinitions[branch]?.takeIf { it.commit == commit && it.primary == primary }?.let { return it.definitions }
        val definitions =
            try {
                configLoader
                    .loadWithBranchLayer(
                        workingDir,
                        ConfigFiles.readCommitted { gitService.showFileAtCommit(commit, it, workingDir) },
                    ).effectiveBuildDefinitions()
            } catch (e: Exception) {
                log.warn(
                    "ignoring the committed {} of branch {} at {}: {}",
                    CONFIG_FILE,
                    branch,
                    commit,
                    e.message ?: e.javaClass.simpleName,
                )
                configLoader.load(workingDir).effectiveBuildDefinitions()
            }
        branchDefinitions[branch] = CachedDefinitions(commit, primary, definitions)
        return definitions
    }

    private class CachedDefinitions(
        val commit: String,
        val primary: WerkatorConfig,
        val definitions: Map<String, BuildDefinition>,
    )

    private fun selects(
        definition: BuildDefinition,
        branch: String,
        headCommitTimes: Lazy<Map<String, Instant>>,
    ): Boolean = definition.trigger.selects(branch, { headCommitTimes.value[branch] }, clock.instant())

    /**
     * Enqueues a build of the branch's origin head unless one is already pending or
     * running, or that commit was already built. Builds run detached in worktrees and
     * move no branch ref themselves, so "already built" is tracked via the result
     * repository, not by resetting the local ref like legacy; the cycle's
     * [fastForwardLocalRefs] runs only after this decision. A new commit for a
     * branch that is still pending/running waits for a later cycle (queue-behind).
     * With `requirePullRequest`, the branch head must match a pull-request head on
     * origin (`refs/pull/<n>/head`); manual `build` commands bypass this gate, and
     * `watcher.pullRequestGate: false` disables it globally for plain-git origins
     * without pull-request refs.
     */
    private fun startBuildIfDue(
        repo: RepoContext,
        branch: String,
        allowSameCommit: Boolean,
        config: WerkatorConfig,
        pullRequestHeads: Lazy<Set<String>>,
        build: String = BuildDefinition.DEFAULT,
    ): Boolean {
        val workingDir = repo.workingDir
        val latest = repo.results.latestFor(BuildDefinition.poolName(branch, build))
        if (latest?.status == BuildStatus.PENDING || latest?.status == BuildStatus.RUNNING) {
            return false
        }
        val commit = gitService.originHeadCommit(branch, workingDir) ?: return false
        if (!allowSameCommit && latest?.commit == commit) {
            return false
        }
        if (config.watcher.pullRequestGate &&
            config.buildSettings(branch, build).requirePullRequest &&
            commit !in pullRequestHeads.value
        ) {
            log.info("not enqueueing branch {}: no pull request has head commit {}", branch, commit)
            return false
        }
        log.info("[{}] enqueueing build {} of branch {} at commit {}", repo.name, build, branch, commit)
        buildExecutor.startBuild(repo, branch, commit, build)
        return true
    }

    /**
     * Fires the due `atTimes` slot of every build definition for its selected branches,
     * once per day and slot per result pool. Rebuilding the already-built commit is
     * the point of a scheduled build.
     */
    private fun enqueueScheduledBuilds(
        repo: RepoContext,
        config: WerkatorConfig,
        originBranches: Set<String>,
        heads: Map<String, String>,
        pullRequestHeads: Lazy<Set<String>>,
        headCommitTimes: Lazy<Map<String, Instant>>,
    ) {
        val autoBuildState = lazy { FileAutoBuildState(repo.workingDir.resolve(AUTO_BUILDS_FILE)) }
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val timeOfDay = LocalTime.ofInstant(now, ZoneOffset.UTC)
        for (branch in originBranches) {
            val scheduled =
                definitionsFor(repo, branch, heads[branch], config).filterValues {
                    it.trigger.atTimes.isNotEmpty()
                }
            for ((buildName, definition) in scheduled) {
                if (!selects(definition, branch, headCommitTimes)) {
                    continue
                }
                val slot = AutoBuildSlots.latestDueSlot(definition.trigger.atTimes, timeOfDay) ?: continue
                val pool = BuildDefinition.poolName(branch, buildName)
                if (autoBuildState.value.isTriggered(pool, today, slot)) {
                    continue
                }
                if (startBuildIfDue(repo, branch, allowSameCommit = true, config, pullRequestHeads, buildName)) {
                    autoBuildState.value.markTriggered(pool, today, slot)
                }
            }
        }
    }

    /**
     * The pre-ADR-0007 `branches.<name>.autoBuild` schedule, kept for compatibility:
     * a daily rebuild of the branch's own pool with its regular command — exactly a
     * `builds` entry with `atTimes` and a single-branch selector would do.
     */
    private fun enqueueDeprecatedAutoBuilds(
        repo: RepoContext,
        config: WerkatorConfig,
        originBranches: Set<String>,
        pullRequestHeads: Lazy<Set<String>>,
    ) {
        val autoBuildBranches =
            config.branches.filter { (branch, branchConfig) ->
                branch != "default" && branchConfig.autoBuild.enabled
            }
        if (autoBuildBranches.isEmpty()) {
            return
        }
        val watch = watchOf(repo)
        if (!watch.warnedDeprecatedAutoBuild) {
            watch.warnedDeprecatedAutoBuild = true
            log.warn(
                "branches.*.autoBuild is deprecated; define a build with atTimes in the builds section instead (branches: {})",
                autoBuildBranches.keys.joinToString(", "),
            )
        }
        val autoBuildState = FileAutoBuildState(repo.workingDir.resolve(AUTO_BUILDS_FILE))
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val timeOfDay = LocalTime.ofInstant(now, ZoneOffset.UTC)
        for ((branch, branchConfig) in autoBuildBranches) {
            val slot = AutoBuildSlots.latestDueSlot(branchConfig.autoBuild.times, timeOfDay) ?: continue
            if (autoBuildState.isTriggered(branch, today, slot)) {
                continue
            }
            if (branch !in originBranches) {
                log.warn("skipping auto build of branch {}: branch is not on origin", branch)
                continue
            }
            if (startBuildIfDue(repo, branch, allowSameCommit = true, config, pullRequestHeads)) {
                autoBuildState.markTriggered(branch, today, slot)
            }
        }
    }

    /** Results first, then artifacts of dropped results, then worktrees of branches gone from origin. */
    private fun prune(
        repo: RepoContext,
        config: WerkatorConfig,
        originBranches: List<String>,
    ) {
        val retentionCutoff =
            config.artifacts.retentionMaxAge
                .takeIf { it.isNotBlank() }
                ?.let { clock.instant().minus(DurationParser.parse(it)) }
        repo.results.prune(
            originBranches,
            config.artifacts.retentionPerBranch,
            config.artifacts.keepLatestGreen,
            retentionCutoff,
        )
        repo.artifactStore.prune(repo.results.history())
        pruneWorktrees(repo, originBranches)
    }

    private fun pruneWorktrees(
        repo: RepoContext,
        originBranches: List<String>,
    ) {
        val workingDir = repo.workingDir
        val worktreesDir = workingDir.resolve(GitWorktreeWorkspaces.WORKTREES_DIR)
        if (!Files.isDirectory(worktreesDir)) {
            return
        }
        val keep = originBranches.map { ArtifactKeys.branchKey(it) }.toMutableSet()
        // never delete under a build that is still queued or executing
        buildExecutor.currentBuilds().forEach { keep += ArtifactKeys.branchKey(it.branch) }
        repo.results
            .latestPerName()
            .filter { it.status == BuildStatus.PENDING || it.status == BuildStatus.RUNNING }
            .forEach { keep += ArtifactKeys.branchKey(it.branch) }
        var removed = false
        Files.list(worktreesDir).use { entries ->
            entries.forEach { entry ->
                if (Files.isDirectory(entry) && entry.fileName.toString() !in keep) {
                    log.info("removing worktree of branch gone from origin: {}", entry.fileName)
                    entry.toFile().deleteRecursively()
                    removed = true
                }
            }
        }
        if (removed) {
            gitService.worktreePrune(workingDir)
        }
    }

    companion object {
        /** Auto-build trigger state next to the build results (replaces legacy `auto-builds.tsv`). */
        const val AUTO_BUILDS_FILE = ".git/werkator/auto-builds.json"

        /** The committed config read per branch for its build definitions. */
        const val CONFIG_FILE = ConfigFiles.COMMITTED
    }
}
