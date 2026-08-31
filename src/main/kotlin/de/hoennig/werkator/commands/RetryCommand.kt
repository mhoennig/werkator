package de.hoennig.werkator.commands

import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.git.GitService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.ExitCode
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * Port of the legacy `--retry` flag as a one-shot command: every branch whose latest
 * build FAILED is rebuilt at its origin head, one after the other, streaming each
 * log. Interrupted and cancelled builds are not retried here — the watcher's startup
 * recovery restarts those.
 */
@Component
@Command(
    name = "retry",
    description = ["Build all branches whose latest build failed"],
    mixinStandardHelpOptions = true,
)
class RetryCommand(
    private val gitService: GitService,
    private val repository: BuildResultRepository,
    private val consoleBuildRunner: ConsoleBuildRunner,
) : Callable<Int> {
    var workingDir: Path = Paths.get(".")

    override fun call(): Int {
        val failed: List<BuildResult>
        try {
            fetchBestEffort()
            failed = repository.latestPerName().filter { it.status == BuildStatus.FAILED }
        } catch (e: Exception) {
            System.err.println("error: ${e.message}")
            return ExitCode.USAGE
        }
        if (failed.isEmpty()) {
            println("no failed builds to retry")
            return ExitCode.OK
        }
        var anyFailed = false
        for (result in failed) {
            val commit = gitService.originHeadCommit(result.branch, workingDir)
            if (commit == null) {
                println("skipping branch ${result.branch}: gone from origin")
                continue
            }
            println("retrying build ${result.name} at commit ${commit.take(12)}")
            // a failed build retries its recorded build definition (settings from the current config)
            val status = consoleBuildRunner.buildAndStream(result.branch, commit, workingDir, result.build)
            if (status != BuildStatus.SUCCESS) {
                anyFailed = true
            }
        }
        return if (anyFailed) ExitCode.SOFTWARE else ExitCode.OK
    }

    private fun fetchBestEffort() {
        try {
            gitService.fetchOrigin(workingDir)
        } catch (e: Exception) {
            System.err.println("warning: fetching origin failed (${e.message}); using the last known origin state")
        }
    }
}
