package de.hoennig.gittally.commands

import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildExecutor
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.build.RunningBuild
import de.hoennig.gittally.config.BuildDefinition
import de.hoennig.gittally.server.UiFormats
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration

/**
 * Runs one build to completion for a CLI command: enqueues it on the async
 * [BuildExecutor], streams the live log to stdout while it runs, and waits until the
 * artifacts are persisted so the CLI exit never cuts off the artifact copy.
 */
@Component
class ConsoleBuildRunner(
    private val buildExecutor: BuildExecutor,
    private val repository: BuildResultRepository,
    private val artifactStore: ArtifactStore,
) {
    var pollIntervalMillis = 200L

    var persistTimeoutMillis = 30_000L

    /** Builds [branch] at [commit], blocking until the build finished; returns the final status. */
    fun buildAndStream(
        branch: String,
        commit: String,
        workingDir: Path = Paths.get("."),
        buildDefinition: String = BuildDefinition.DEFAULT,
    ): BuildStatus {
        val build = buildExecutor.startBuild(branch, commit, workingDir, buildDefinition)
        var printed = 0L
        var result: BuildResult? = null
        while (result?.status?.isTerminal != true) {
            printed += printNewLogBytes(build.liveLogFile, printed)
            result = repository.history().firstOrNull { it.artifactKey == build.artifactKey }
            if (result?.status?.isTerminal != true) {
                Thread.sleep(pollIntervalMillis)
            }
        }
        drainAfterBuild(build, printed)
        val after = result.duration?.let { " after ${UiFormats.duration(it)}" } ?: ""
        println("build of branch $branch: ${result.status.name.lowercase()}$after")
        return result.status
    }

    /**
     * The live log is complete once the status is terminal, but the executor still
     * persists the artifacts afterwards, deleting the staging directory at the end.
     * Waiting for that keeps the JVM alive until the artifact copy finished; the
     * remaining log bytes come from the staging file or, once persisted, from the
     * stored copy (which is byte-identical, so the offset carries over).
     */
    private fun drainAfterBuild(
        build: RunningBuild,
        alreadyPrinted: Long,
    ) {
        var printed = alreadyPrinted
        val deadline = System.nanoTime() + Duration.ofMillis(persistTimeoutMillis).toNanos()
        while (Files.exists(build.stagingDir)) {
            printed += printNewLogBytes(build.liveLogFile, printed)
            if (System.nanoTime() >= deadline) {
                System.err.println(
                    "warning: artifacts of ${build.artifactKey} were not persisted within ${persistTimeoutMillis / 1000}s",
                )
                return
            }
            Thread.sleep(pollIntervalMillis)
        }
        artifactStore.artifactDir(build.artifactKey)?.let { artifactDir ->
            printNewLogBytes(artifactDir.resolve(BuildExecutor.LIVE_LOG_FILE), printed)
        }
    }

    /** Copies everything after [offset] to stdout; a missing or vanished file just yields 0 bytes. */
    private fun printNewLogBytes(
        file: Path,
        offset: Long,
    ): Long {
        if (!Files.isRegularFile(file)) {
            return 0
        }
        return try {
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                channel.position(offset)
                val copied = Channels.newInputStream(channel).copyTo(System.out)
                System.out.flush()
                copied
            }
        } catch (_: IOException) {
            0
        }
    }
}
