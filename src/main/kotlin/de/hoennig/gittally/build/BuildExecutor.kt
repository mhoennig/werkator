package de.hoennig.gittally.build

import de.hoennig.gittally.config.BranchConfig
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.gitea.GiteaClient
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
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
 * Runs builds asynchronously: up to `builds.maxConcurrent` branches at the same time
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

    /** Global concurrency limit; sized from `builds.maxConcurrent` on first use. */
    @Volatile
    private var slots: Semaphore? = null

    /** The builds currently executing, newest last (queued builds are PENDING in the repository). */
    fun currentBuilds(): List<RunningBuild> = builds.values.filter { it.running }.map { it.runningBuild }

    /**
     * Persists a PENDING result and queues the build; returns immediately.
     * A build of the same branch waits until the branch's previous build finished;
     * builds of other branches run concurrently while slots are free.
     */
    fun startBuild(
        branch: String,
        commit: String,
        workingDir: Path = Paths.get("."),
    ): RunningBuild {
        val startedAt = Instant.now()
        val stagingDir = Files.createTempDirectory("gittally-build-")
        val runningBuild =
            RunningBuild(
                branch = branch,
                commit = commit,
                artifactKey = ArtifactKeys.buildKey(branch, startedAt),
                startedAt = startedAt,
                stagingDir = stagingDir,
                liveLogFile = stagingDir.resolve(LIVE_LOG_FILE),
            )
        val pending =
            BuildResult(
                branch = branch,
                commit = commit,
                status = BuildStatus.PENDING,
                startedAt = startedAt,
                duration = null,
                artifactKey = runningBuild.artifactKey,
            )
        repository.append(pending)
        eventPublisher.publishEvent(BuildStatusChangedEvent(pending))
        val build = ActiveBuild(runningBuild, workingDir)
        builds[runningBuild.artifactKey] = build
        publishGiteaStatus(build, BuildStatus.PENDING, duration = null)
        branchWorkers
            .computeIfAbsent(branch) { serialWorker(it) }
            .submit { execute(build) }
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

    private fun execute(build: ActiveBuild) {
        var slot: Semaphore? = null
        var finalStatus = BuildStatus.FAILED
        var workspace: Path? = null
        try {
            slot = slotsFor(build.workingDir)
            slot.acquire()
            if (build.cancelled.get()) {
                finalStatus = BuildStatus.CANCELLED
                return
            }
            build.running = true
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
                    else -> BuildStatus.FAILED
                }
        } catch (e: Exception) {
            finalStatus = if (build.cancelled.get()) BuildStatus.CANCELLED else BuildStatus.FAILED
            log.error("build of branch {} crashed", build.runningBuild.branch, e)
            appendToLiveLog(build, "\nbuild crashed: ${e.message}\n")
        } finally {
            val duration = Duration.between(build.runningBuild.startedAt, Instant.now())
            val result = transition(build, finalStatus, duration)
            try {
                artifactStore.persist(result, build.runningBuild.stagingDir, workspace)
            } catch (e: Exception) {
                log.warn("could not persist artifacts of {}: {}", result.artifactKey, e.message)
            }
            builds.remove(build.runningBuild.artifactKey)
            slot?.release()
        }
    }

    /**
     * The semaphore is sized once from the first build's config;
     * changing `builds.maxConcurrent` requires a restart.
     */
    private fun slotsFor(workingDir: Path): Semaphore {
        slots?.let { return it }
        synchronized(this) {
            slots?.let { return it }
            val maxConcurrent =
                configLoader
                    .load(workingDir)
                    .builds.maxConcurrent
                    .coerceAtLeast(1)
            return Semaphore(maxConcurrent, true).also { slots = it }
        }
    }

    private fun serialWorker(branch: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "gittally-build-${ArtifactKeys.branchKey(branch)}").apply { isDaemon = true }
        }

    private fun runBuildCommands(
        build: ActiveBuild,
        workspace: Path,
    ): Int {
        val branchConfig = branchConfig(build.runningBuild.branch, build.workingDir, workspace)
        val stagingDir = build.runningBuild.stagingDir
        Files.newOutputStream(stagingDir.resolve(branchConfig.stdoutLog)).use { stdoutLog ->
            Files.newOutputStream(stagingDir.resolve(branchConfig.stderrLog)).use { stderrLog ->
                Files.newOutputStream(build.runningBuild.liveLogFile).use { liveLog ->
                    writeLiveLogHeader(liveLog, build.runningBuild, branchConfig, workspace)
                    if (branchConfig.cleanCommand.isNotBlank()) {
                        val cleanExitCode =
                            runCommand(build, branchConfig, branchConfig.cleanCommand, workspace, stdoutLog, stderrLog, liveLog)
                        if (cleanExitCode != 0) {
                            return cleanExitCode
                        }
                    }
                    if (build.cancelled.get()) {
                        return CANCELLED_EXIT_CODE
                    }
                    return runCommand(build, branchConfig, branchConfig.buildCommand, workspace, stdoutLog, stderrLog, liveLog)
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
            )
        build.process = process
        if (build.cancelled.get()) {
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
        thread(isDaemon = true, name = "gittally-build-log") {
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
                it.copy(status = status, duration = duration ?: it.duration)
            } ?: BuildResult(
                branch = runningBuild.branch,
                commit = runningBuild.commit,
                status = status,
                startedAt = runningBuild.startedAt,
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
            )
        } catch (e: Exception) {
            log.warn("could not publish Gitea status {} for {}: {}", status, build.runningBuild.commit, e.message)
        }
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
        workspace: Path,
    ) {
        val header =
            buildString {
                appendLine("building branch: ${runningBuild.branch}")
                appendLine("commit: ${runningBuild.commit}")
                appendLine("started: ${runningBuild.startedAt}")
                appendLine("workspace: $workspace")
                appendLine("build command: ${branchConfig.buildCommand}")
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

    /** The build config for [branch], with the build [worktree]'s `.gittally.yml` layered on top (see [ConfigLoader.loadForWorktree]). */
    private fun branchConfig(
        branch: String,
        workingDir: Path,
        worktree: Path,
    ): BranchConfig {
        val branches = configLoader.loadForWorktree(workingDir, worktree).branches
        return branches[branch] ?: branches["default"] ?: BranchConfig()
    }

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
    }
}
