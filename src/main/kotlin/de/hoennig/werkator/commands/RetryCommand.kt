package de.hoennig.werkator.commands

import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoRegistry
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.ExitCode
import picocli.CommandLine.Mixin
import java.nio.file.Path
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
    private val consoleBuildRunner: ConsoleBuildRunner,
    private val registry: RepoRegistry,
) : Callable<Int> {
    @Mixin
    var repoOption = RepoOption()

    private lateinit var repo: RepoContext

    private val workingDir: Path
        get() = repo.workingDir

    override fun call(): Int {
        val failed: List<BuildResult>
        try {
            repo = repoOption.select(registry)
            fetchBestEffort()
            failed = repo.results.latestPerName().filter { it.status == BuildStatus.FAILED }
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
            val status = consoleBuildRunner.buildAndStream(repo, result.branch, commit, result.build)
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
