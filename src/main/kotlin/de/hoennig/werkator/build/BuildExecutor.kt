package de.hoennig.werkator.build

import de.hoennig.werkator.config.BranchConfig
import de.hoennig.werkator.config.BuildDefinition
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.gitea.GiteaClient
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs builds asynchronously: up to `executor.maxConcurrent` branches at the same time
 * (default 1), but never more than one build per branch. Each branch builds in its
 * own git worktree via [BranchWorkspaces], never in the primary checkout.
 * Every status transition is persisted via the [BuildResultRepository], published
 * to Gitea (non-fatal), and emitted as a [BuildStatusChangedEvent].
 */
@Service
class BuildExecutor(
    private val repository: BuildResultRepository,
    private val configLoader: ConfigLoader,
    private val giteaClient: GiteaClient,
    private val buildRunner: BuildRunner,
    private val workspaces: BranchWorkspaces,
    private val artifactStore: ArtifactStore,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(BuildExecutor::class.java)

    /** One serial worker per branch enforces at most one build per branch. */
    private val branchWorkers = ConcurrentHashMap<String, ExecutorService>()

    /** All accepted, not yet finished builds by artifact key — queued and running. */
    private val builds = ConcurrentHashMap<String, ActiveBuild>()

    /** Global concurrency limit; sized from `executor.maxConcurrent` on first use. */
    @Volatile
    private var slots: Semaphore? = null

    /** Set on context close; in-flight builds then finish as INTERRUPTED instead of FAILED. */
    private val shuttingDown = AtomicBoolean(false)

    /** The builds currently executing, newest last (queued builds are PENDING in the repository). */
    fun currentBuilds(): List<RunningBuild> = builds.values.filter { it.running }.map { it.runningBuild }

    /**
     * Persists a PENDING result and queues the build; returns immediately.
     * A build of the same branch waits until the branch's previous build finished;
     * builds of other branches run concurrently while slots are free.
     * While a build of the same branch and commit is already queued or executing (and
     * not cancel-requested), that build is returned instead of stacking a duplicate —
     * a double-triggered UI restart must not queue the same commit twice. Re-running
     * a *finished* build stays possible; this only guards the active queue.
     * The [build] names the build definition (job, ADR 0007) this run belongs to; its
     * settings — command overrides and the result pool `<branch>@<build>` — are
     * resolved from the current configuration when the build starts executing. A
     * non-default build has its own pool, so it never counts as a duplicate of the
     * branch's regular build of the same commit; it still runs in the branch's
     * worktree, serialized with the branch's other builds.
     */
    fun startBuild(
        branch: String,
        commit: String,
        workingDir: Path = Paths.get("."),
        build: String = BuildDefinition.DEFAULT,
    ): RunningBuild {
        val name = BuildDefinition.poolName(branch, build)
        val duplicate =
            builds.values.firstOrNull {
                !it.cancelled.get() &&
                    it.runningBuild.name == name &&
                    it.runningBuild.commit == commit
            }
        if (duplicate != null) {
            log.info("build of branch {} at commit {} is already queued or running; not queueing a duplicate", branch, commit)
            return duplicate.runningBuild
        }
        val startedAt = Instant.now()
        val stagingDir = Files.createTempDirectory("werkator-build-")
        val runningBuild =
            RunningBuild(
                branch = branch,
                build = build,
                commit = commit,
                artifactKey = ArtifactKeys.buildKey(name, startedAt),
                startedAt = startedAt,
                stagingDir = stagingDir,
                liveLogFile = stagingDir.resolve(LIVE_LOG_FILE),
            )
        val pending =
            BuildResult(
                branch = branch,
                build = build,
                commit = commit,
                status = BuildStatus.PENDING,
                startedAt = startedAt,
                duration = null,
                artifactKey = runningBuild.artifactKey,
            )
        repository.append(pending)
        eventPublisher.publishEvent(BuildStatusChangedEvent(pending))
        val activeBuild = ActiveBuild(runningBuild, workingDir)
        builds[runningBuild.artifactKey] = activeBuild
        publishGiteaStatus(activeBuild, BuildStatus.PENDING, duration = null)
        branchWorkers
            .computeIfAbsent(branch) { serialWorker(it) }
            .submit { execute(activeBuild) }
        return runningBuild
    }

    /**
     * Requests cancellation of the build with [artifactKey] and terminates its process
     * tree (TERM, wait, KILL — like legacy `terminate_process_tree`). A queued build
     * is recorded as CANCELLED once its worker picks it up.
     * Returns false when no such build is queued or running.
     */
    fun cancel(artifactKey: String): Boolean {
        val build = builds[artifactKey] ?: return false
        build.cancelled.set(true)
        build.process?.let { destroyProcessTree(it) }
        return true
    }

    /**
     * Runs when the application context closes (e.g. systemd SIGTERM): terminates the
     * process trees of all executing builds and waits (bounded) until their INTERRUPTED
     * results are persisted, so a shutdown is never recorded as a build failure.
     * Builds still queued stay PENDING; the watcher's startup recovery re-enqueues
     * both PENDING and INTERRUPTED builds after the restart.
     */
    @EventListener(ContextClosedEvent::class)
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return
        }
        val executing = builds.values.filter { it.running }
        if (executing.isEmpty()) {
            return
        }
        log.info("shutdown requested; interrupting {} executing build(s)", executing.size)
        executing.forEach { build -> build.process?.let { destroyProcessTree(it) } }
        val deadline = System.nanoTime() + Duration.ofMillis(SHUTDOWN_DRAIN_TIMEOUT_MILLIS).toNanos()
        while (executing.any { builds.containsKey(it.runningBuild.artifactKey) } && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        executing
            .filter { builds.containsKey(it.runningBuild.artifactKey) }
            .forEach { log.warn("build of branch {} was not recorded as interrupted in time", it.runningBuild.branch) }
    }

    private fun execute(build: ActiveBuild) {
        var slot: Semaphore? = null
        // null keeps the persisted status untouched (a queued build stays PENDING over a shutdown)
        var finalStatus: BuildStatus? = BuildStatus.FAILED
        var workspace: Path? = null
        try {
            slot = slotsFor(build.workingDir)
            slot.acquire()
            if (build.cancelled.get()) {
                finalStatus = BuildStatus.CANCELLED
                return
            }
            if (shuttingDown.get()) {
                finalStatus = null
                return
            }
            build.running = true
            build.runningBuild.runningSince = Instant.now()
            transition(build, BuildStatus.RUNNING, duration = null)
            val preparedWorkspace =
                workspaces.prepare(
                    branch = build.runningBuild.branch,
                    commit = build.runningBuild.commit,
                    repoDir = build.workingDir,
                )
            workspace = preparedWorkspace
            val exitCode = runBuildCommands(build, preparedWorkspace)
            finalStatus =
                when {
                    build.cancelled.get() -> BuildStatus.CANCELLED
                    exitCode == 0 -> BuildStatus.SUCCESS
                    shuttingDown.get() -> BuildStatus.INTERRUPTED
                    else -> BuildStatus.FAILED
                }
        } catch (e: Exception) {
            finalStatus =
                when {
                    build.cancelled.get() -> BuildStatus.CANCELLED
                    shuttingDown.get() -> BuildStatus.INTERRUPTED
                    else -> BuildStatus.FAILED
                }
            if (finalStatus == BuildStatus.FAILED) {
                log.error("build of branch {} crashed", build.runningBuild.branch, e)
                appendToLiveLog(build, "\nbuild crashed: ${e.message}\n")
            }
        } finally {
            if (finalStatus == BuildStatus.INTERRUPTED) {
                log.info("build of branch {} interrupted by shutdown", build.runningBuild.branch)
                appendToLiveLog(build, "\nbuild interrupted by shutdown\n")
            }
            if (finalStatus != null) {
                // pure build time, without the queue wait; null when the build never started executing
                val duration = build.runningBuild.runningSince?.let { Duration.between(it, Instant.now()) }
                val result = transition(build, finalStatus, duration)
                try {
                    artifactStore.persist(result, build.runningBuild.stagingDir, workspace)
                } catch (e: Exception) {
                    log.warn("could not persist artifacts of {}: {}", result.artifactKey, e.message)
                }
            }
            builds.remove(build.runningBuild.artifactKey)
            slot?.release()
        }
    }

    /**
     * The semaphore is sized once from the first build's config;
     * changing `executor.maxConcurrent` requires a restart.
     */
    private fun slotsFor(workingDir: Path): Semaphore {
        slots?.let { return it }
        synchronized(this) {
            slots?.let { return it }
            val maxConcurrent =
                configLoader
                    .load(workingDir)
                    .executor.maxConcurrent
                    .coerceAtLeast(1)
            return Semaphore(maxConcurrent, true).also { slots = it }
        }
    }

    private fun serialWorker(branch: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "werkator-build-${ArtifactKeys.branchKey(branch)}").apply { isDaemon = true }
        }

    private fun runBuildCommands(
        build: ActiveBuild,
        workspace: Path,
    ): Int {
        val branchConfig = buildConfig(build.runningBuild, build.workingDir, workspace)
        val buildCommand = branchConfig.buildCommand
        val stagingDir = build.runningBuild.stagingDir
        Files.newOutputStream(stagingDir.resolve(branchConfig.stdoutLog)).use { stdoutLog ->
            Files.newOutputStream(stagingDir.resolve(branchConfig.stderrLog)).use { stderrLog ->
                Files.newOutputStream(build.runningBuild.liveLogFile).use { liveLog ->
                    writeLiveLogHeader(liveLog, build.runningBuild, branchConfig, buildCommand, workspace)
                    if (branchConfig.cleanCommand.isNotBlank()) {
                        val cleanExitCode =
                            runCommand(build, branchConfig, branchConfig.cleanCommand, workspace, stdoutLog, stderrLog, liveLog)
                        if (cleanExitCode != 0) {
                            return cleanExitCode
                        }
                    }
                    if (build.cancelled.get() || shuttingDown.get()) {
                        return CANCELLED_EXIT_CODE
                    }
                    return runCommand(build, branchConfig, buildCommand, workspace, stdoutLog, stderrLog, liveLog)
                }
            }
        }
    }

    private fun runCommand(
        build: ActiveBuild,
        branchConfig: BranchConfig,
        command: String,
        workspace: Path,
        stdoutLog: OutputStream,
        stderrLog: OutputStream,
        liveLog: OutputStream,
    ): Int {
        val process =
            buildRunner.start(
                command = command,
                workingDir = workspace,
                environment = mapOf("branch" to build.runningBuild.branch),
                repoDir = build.workingDir,
                branchConfig = branchConfig,
                onAuxProcess = { aux ->
                    // preparation phases (e.g. a Docker image build) must die on cancellation
                    // and shutdown too, otherwise they block their slot until they finish
                    build.process = aux
                    if (build.cancelled.get() || shuttingDown.get()) {
                        destroyProcessTree(aux)
                    }
                },
            )
        build.process = process
        if (build.cancelled.get() || shuttingDown.get()) {
            destroyProcessTree(process)
        }
        val stdoutPump = pump(process.inputStream, stdoutLog, liveLog)
        val stderrPump = pump(process.errorStream, stderrLog, liveLog)
        try {
            return process.waitFor()
        } finally {
            build.process = null
            stdoutPump.join(PUMP_DRAIN_TIMEOUT_MILLIS)
            stderrPump.join(PUMP_DRAIN_TIMEOUT_MILLIS)
        }
    }

    /** Copies process output to both sinks as it arrives, flushing so the live log grows during the build. */
    private fun pump(
        input: InputStream,
        vararg sinks: OutputStream,
    ): Thread =
        thread(isDaemon = true, name = "werkator-build-log") {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) {
                        break
                    }
                    for (sink in sinks) {
                        synchronized(sink) {
                            sink.write(buffer, 0, length)
                            sink.flush()
                        }
                    }
                }
            } catch (_: IOException) {
                // the stream closes when the process dies; nothing left to copy
            }
        }

    /** TERM to all descendants and the root, wait up to 2s, then KILL survivors. */
    private fun destroyProcessTree(process: Process) {
        val root = process.toHandle()
        val tree = root.descendants().toList() + root
        tree.forEach { it.destroy() }
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (tree.any { it.isAlive } && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    private fun transition(
        build: ActiveBuild,
        status: BuildStatus,
        duration: Duration?,
    ): BuildResult {
        val runningBuild = build.runningBuild
        val updated =
            repository.updateByArtifactKey(runningBuild.artifactKey) {
                it.copy(
                    status = status,
                    runningSince = runningBuild.runningSince ?: it.runningSince,
                    duration = duration ?: it.duration,
                )
            } ?: BuildResult(
                branch = runningBuild.branch,
                build = runningBuild.build,
                commit = runningBuild.commit,
                status = status,
                startedAt = runningBuild.startedAt,
                runningSince = runningBuild.runningSince,
                duration = duration,
                artifactKey = runningBuild.artifactKey,
            ).also { repository.append(it) }
        eventPublisher.publishEvent(BuildStatusChangedEvent(updated))
        publishGiteaStatus(build, status, duration)
        return updated
    }

    private fun publishGiteaStatus(
        build: ActiveBuild,
        status: BuildStatus,
        duration: Duration?,
    ) {
        try {
            giteaClient.publishStatus(
                sha = build.runningBuild.commit,
                status = status,
                description = description(status, duration),
                targetUrl = null,
                workingDir = build.workingDir,
                // from the primary config, not the worktree: statusContext is pinned, so a
                // branch cannot report under a check name it was not given
                context = statusContextOf(build),
            )
        } catch (e: Exception) {
            log.warn("could not publish Gitea status {} for {}: {}", status, build.runningBuild.commit, e.message)
        }
    }

    /** The build's own Gitea status context, empty when it uses the repository-wide one. */
    private fun statusContextOf(build: ActiveBuild): String =
        try {
            configLoader
                .load(build.workingDir)
                .buildSettings(build.runningBuild.branch, build.runningBuild.build)
                .statusContext
        } catch (e: Exception) {
            log.warn("could not resolve the status context of {}: {}", build.runningBuild.branch, e.message)
            ""
        }

    private fun description(
        status: BuildStatus,
        duration: Duration?,
    ): String {
        val after = duration?.let { " after ${formatDuration(it)}" } ?: ""
        return when (status) {
            BuildStatus.PENDING -> "build queued"
            BuildStatus.RUNNING -> "build running"
            BuildStatus.SUCCESS -> "build succeeded$after"
            BuildStatus.FAILED -> "build failed$after"
            BuildStatus.INTERRUPTED -> "build interrupted$after"
            BuildStatus.CANCELLED -> "build cancelled$after"
        }
    }

    private fun formatDuration(duration: Duration): String = "%02d:%02d".format(duration.toMinutes(), duration.toSecondsPart())

    private fun writeLiveLogHeader(
        liveLog: OutputStream,
        runningBuild: RunningBuild,
        branchConfig: BranchConfig,
        buildCommand: String,
        workspace: Path,
    ) {
        val header =
            buildString {
                appendLine("building branch: ${runningBuild.branch}")
                if (runningBuild.build != BuildDefinition.DEFAULT) {
                    appendLine("build: ${runningBuild.build} (recorded as ${runningBuild.name})")
                }
                appendLine("commit: ${runningBuild.commit}")
                appendLine("started: ${runningBuild.startedAt}")
                appendLine("workspace: $workspace")
                appendLine("build command: $buildCommand")
                if (branchConfig.cleanCommand.isNotBlank()) {
                    appendLine("clean command: ${branchConfig.cleanCommand}")
                }
                appendLine()
            }
        synchronized(liveLog) {
            liveLog.write(header.toByteArray())
            liveLog.flush()
        }
    }

    private fun appendToLiveLog(
        build: ActiveBuild,
        message: String,
    ) {
        try {
            Files.writeString(
                build.runningBuild.liveLogFile,
                message,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: IOException) {
            log.warn("could not append to live log of {}: {}", build.runningBuild.artifactKey, e.message)
        }
    }

    /**
     * The effective settings of this run: the branch config with the build [worktree]'s
     * `.werkator.yml` layered on top (see [ConfigLoader.loadForWorktree]), then the
     * build definition's overrides applied last — the job wins, and it always comes
     * from the primary config (`builds` is a pinned section). An unknown build name
     * (a stale result whose job was removed) falls back to the plain branch settings.
     */
    private fun buildConfig(
        runningBuild: RunningBuild,
        workingDir: Path,
        worktree: Path,
    ): BranchConfig =
        configLoader
            .loadForWorktree(workingDir, worktree)
            .buildSettings(runningBuild.branch, runningBuild.build)

    private class ActiveBuild(
        val runningBuild: RunningBuild,
        val workingDir: Path,
    ) {
        val cancelled = AtomicBoolean(false)

        @Volatile
        var running = false

        @Volatile
        var process: Process? = null
    }

    companion object {
        /** Name of the combined live log inside the staging directory. */
        const val LIVE_LOG_FILE = "build.log"
        private const val CANCELLED_EXIT_CODE = 130
        private const val PUMP_DRAIN_TIMEOUT_MILLIS = 10_000L
        private const val SHUTDOWN_DRAIN_TIMEOUT_MILLIS = 20_000L
    }
}
