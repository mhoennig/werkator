package de.hoennig.gittally.watcher

import de.hoennig.gittally.build.ArtifactKeys
import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildExecutor
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.build.GitWorktreeWorkspaces
import de.hoennig.gittally.config.BranchConfig
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.config.DurationParser
import de.hoennig.gittally.config.GitTallyConfig
import de.hoennig.gittally.git.GitService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Replaces the legacy blocking main loop: a non-blocking fixed-delay poll cycle that
 * fetches origin, enqueues due branches via the async [BuildExecutor], and prunes
 * retention — it never waits for a build and never touches the primary checkout.
 * The loop only runs after an explicit [start] (server/watch mode, step 07);
 * nothing is scheduled during CLI commands or tests.
 */
@Service
class Watcher(
    private val gitService: GitService,
    private val buildExecutor: BuildExecutor,
    private val repository: BuildResultRepository,
    private val artifactStore: ArtifactStore,
    private val configLoader: ConfigLoader,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Watcher::class.java)

    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var state = WatcherState()

    fun state(): WatcherState = state

    /**
     * Runs the startup recovery and schedules the poll loop with the fixed delay
     * `watcher.pollInterval`; the first poll runs immediately.
     */
    @Synchronized
    fun start(workingDir: Path = Paths.get(".")) {
        check(scheduler == null) { "watcher is already running" }
        recoverOnStartup(workingDir)
        val interval = DurationParser.parse(configLoader.load(workingDir).watcher.pollInterval)
        scheduler =
            Executors
                .newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "gittally-watcher").apply { isDaemon = true }
                }.also {
                    it.scheduleWithFixedDelay({ pollSafely(workingDir) }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
                }
        state = state.copy(running = true)
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
    fun recoverOnStartup(workingDir: Path = Paths.get(".")) {
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
                .latestPerBranch()
                .filter { it.status == BuildStatus.INTERRUPTED || it.status == BuildStatus.PENDING }
        for (result in restartable) {
            val commit = gitService.originHeadCommit(result.branch, workingDir)
            if (commit == null) {
                log.info("not restarting build of branch {}: branch is gone from origin", result.branch)
                continue
            }
            if (result.status == BuildStatus.PENDING) {
                // the executor queue did not survive the restart; the re-enqueued build supersedes the stale entry
                repository.updateByArtifactKey(result.artifactKey) { it.copy(status = BuildStatus.INTERRUPTED) }
            }
            log.info("restarting unfinished build of branch {}", result.branch)
            buildExecutor.startBuild(result.branch, commit, workingDir)
        }
    }

    /**
     * One poll cycle, never blocking on a build: fetch origin (on failure: log, expose
     * in [state], retry next cycle), enqueue due branches — changed local branches
     * first, then recent new origin branches, then due auto-build slots — and finally
     * prune results, artifacts, and worktrees of branches gone from origin.
     */
    fun poll(workingDir: Path = Paths.get(".")) {
        val startedAt = clock.instant()
        try {
            gitService.fetchOrigin(workingDir)
        } catch (e: Exception) {
            log.warn("fetching origin failed; retrying next cycle: {}", e.message)
            state = state.copy(lastPollAt = startedAt, lastFetchError = e.message ?: e.javaClass.simpleName)
            return
        }
        val config = configLoader.load(workingDir)
        val originBranches = gitService.originBranches(workingDir)
        enqueueDueBranches(config, originBranches.toSet(), workingDir)
        prune(config, originBranches, workingDir)
        state =
            state.copy(
                lastPollAt = startedAt,
                lastFetchError = null,
                lastPollError = null,
                queuedBranches =
                    repository
                        .latestPerBranch()
                        .filter { it.status == BuildStatus.PENDING || it.status == BuildStatus.RUNNING }
                        .map { it.branch },
            )
    }

    private fun pollSafely(workingDir: Path) {
        try {
            poll(workingDir)
        } catch (e: Exception) {
            log.error("poll cycle failed", e)
            state = state.copy(lastPollAt = clock.instant(), lastPollError = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun enqueueDueBranches(
        config: GitTallyConfig,
        originBranches: Set<String>,
        workingDir: Path,
    ) {
        // one ls-remote per poll cycle at most, and only when a due branch requires a pull request
        val pullRequestHeads = lazy { gitService.pullRequestHeads(workingDir) }
        val changedLocal =
            gitService
                .localBranches(workingDir)
                .filter { it in originBranches && gitService.hasNewCommits(it, workingDir) }
        val newOrigin =
            gitService.newOriginBranches(DurationParser.parse(config.watcher.newBranchMaxAge), workingDir)
        for (branch in (changedLocal + newOrigin).distinct()) {
            startBuildIfDue(branch, allowSameCommit = false, config, pullRequestHeads, workingDir)
        }
        enqueueAutoBuilds(config, originBranches, pullRequestHeads, workingDir)
    }

    /**
     * Enqueues a build of the branch's origin head unless one is already pending or
     * running, or that commit was already built. Builds run detached in worktrees and
     * never move local branch refs, so "already built" is tracked via the result
     * repository, not by resetting the local ref like legacy. A new commit for a
     * branch that is still pending/running waits for a later cycle (queue-behind).
     * With `requirePullRequest`, the branch head must match a pull-request head on
     * origin (`refs/pull/<n>/head`); manual `build` commands bypass this gate, and
     * `watcher.pullRequestGate: false` disables it globally for plain-git origins
     * without pull-request refs.
     */
    private fun startBuildIfDue(
        branch: String,
        allowSameCommit: Boolean,
        config: GitTallyConfig,
        pullRequestHeads: Lazy<Set<String>>,
        workingDir: Path,
    ): Boolean {
        val latest = repository.latestFor(branch)
        if (latest?.status == BuildStatus.PENDING || latest?.status == BuildStatus.RUNNING) {
            return false
        }
        val commit = gitService.originHeadCommit(branch, workingDir) ?: return false
        if (!allowSameCommit && latest?.commit == commit) {
            return false
        }
        if (config.watcher.pullRequestGate &&
            branchConfig(config, branch).requirePullRequest &&
            commit !in pullRequestHeads.value
        ) {
            log.info("not enqueueing branch {}: no pull request has head commit {}", branch, commit)
            return false
        }
        log.info("enqueueing build of branch {} at commit {}", branch, commit)
        buildExecutor.startBuild(branch, commit, workingDir)
        return true
    }

    private fun branchConfig(
        config: GitTallyConfig,
        branch: String,
    ): BranchConfig = config.branches[branch] ?: config.branches["default"] ?: BranchConfig()

    private fun enqueueAutoBuilds(
        config: GitTallyConfig,
        originBranches: Set<String>,
        pullRequestHeads: Lazy<Set<String>>,
        workingDir: Path,
    ) {
        val autoBuildBranches =
            config.branches.filter { (branch, branchConfig) ->
                branch != "default" && branchConfig.autoBuild.enabled
            }
        if (autoBuildBranches.isEmpty()) {
            return
        }
        val autoBuildState = FileAutoBuildState(workingDir.resolve(AUTO_BUILDS_FILE))
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
            // rebuilding the already-built commit is the point of an auto build
            if (startBuildIfDue(branch, allowSameCommit = true, config, pullRequestHeads, workingDir)) {
                autoBuildState.markTriggered(branch, today, slot)
            }
        }
    }

    /** Results first, then artifacts of dropped results, then worktrees of branches gone from origin. */
    private fun prune(
        config: GitTallyConfig,
        originBranches: List<String>,
        workingDir: Path,
    ) {
        val retentionCutoff =
            config.artifacts.retentionMaxAge
                .takeIf { it.isNotBlank() }
                ?.let { clock.instant().minus(DurationParser.parse(it)) }
        repository.prune(
            originBranches,
            config.artifacts.retentionPerBranch,
            config.artifacts.keepLatestGreen,
            retentionCutoff,
        )
        artifactStore.prune(repository.history())
        pruneWorktrees(originBranches, workingDir)
    }

    private fun pruneWorktrees(
        originBranches: List<String>,
        workingDir: Path,
    ) {
        val worktreesDir = workingDir.resolve(GitWorktreeWorkspaces.WORKTREES_DIR)
        if (!Files.isDirectory(worktreesDir)) {
            return
        }
        val keep = originBranches.map { ArtifactKeys.branchKey(it) }.toMutableSet()
        // never delete under a build that is still queued or executing
        buildExecutor.currentBuilds().forEach { keep += ArtifactKeys.branchKey(it.branch) }
        repository
            .latestPerBranch()
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
        const val AUTO_BUILDS_FILE = ".git/gittally/auto-builds.json"
    }
}
