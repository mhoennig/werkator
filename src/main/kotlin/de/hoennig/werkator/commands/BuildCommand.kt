package de.hoennig.werkator.commands

import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.git.GitService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.ExitCode
import picocli.CommandLine.Parameters
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * One-shot build for interactive use, replacing the legacy `--stay` mode and explicit
 * branch arguments: fetch, build the branch in its worktree, stream the log, exit
 * with the build's outcome. Unlike the watcher it builds even without new commits.
 */
@Component
@Command(
    name = "build",
    description = ["Fetch and build a branch once, streaming the build log"],
    mixinStandardHelpOptions = true,
)
class BuildCommand(
    private val gitService: GitService,
    private val consoleBuildRunner: ConsoleBuildRunner,
) : Callable<Int> {
    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "<branch>",
        description = ["branch to build; a unique name fragment resolves (default: the current branch)"],
    )
    var branchFragment: String? = null

    var workingDir: Path = Paths.get(".")

    override fun call(): Int {
        val branch: String
        val commit: String
        try {
            fetchBestEffort()
            branch = resolveBranch() ?: return ExitCode.USAGE
            commit = commitToBuild(branch) ?: return ExitCode.USAGE
        } catch (e: Exception) {
            System.err.println("error: ${e.message}")
            return ExitCode.USAGE
        }
        println("building branch $branch at commit ${commit.take(12)}")
        val status = consoleBuildRunner.buildAndStream(branch, commit, workingDir)
        return if (status == BuildStatus.SUCCESS) ExitCode.OK else ExitCode.SOFTWARE
    }

    /** A one-shot build should still work offline, from the last fetched origin state. */
    private fun fetchBestEffort() {
        try {
            gitService.fetchOrigin(workingDir)
        } catch (e: Exception) {
            System.err.println("warning: fetching origin failed (${e.message}); using the last known origin state")
        }
    }

    private fun resolveBranch(): String? {
        val fragment = branchFragment
        if (fragment == null) {
            val current = gitService.currentBranch(workingDir)
            if (current == null) {
                System.err.println("error: HEAD is detached; specify a branch")
            }
            return current
        }
        val candidates = (gitService.localBranches(workingDir) + gitService.originBranches(workingDir)).distinct()
        return when (val match = BranchNameResolution.resolve(fragment, candidates)) {
            is BranchNameResolution.Resolved -> match.branch
            is BranchNameResolution.Ambiguous -> {
                System.err.println("error: multiple branches match '$fragment':")
                match.candidates.forEach { System.err.println("  $it") }
                null
            }
            BranchNameResolution.NoMatch -> {
                System.err.println("error: no local or origin branch matches '$fragment'")
                null
            }
        }
    }

    /**
     * Like the legacy flow: origin's head when the branch has new commits there,
     * otherwise the local head — so unpushed local commits build as they are.
     * Origin-only branches build origin's head.
     */
    private fun commitToBuild(branch: String): String? {
        val localHead = gitService.localHeadCommit(branch, workingDir)
        val commit =
            when {
                localHead == null -> gitService.originHeadCommit(branch, workingDir)
                gitService.hasNewCommits(branch, workingDir) ->
                    gitService.originHeadCommit(branch, workingDir) ?: localHead
                else -> localHead
            }
        if (commit == null) {
            System.err.println("error: branch $branch has neither a local nor an origin head commit")
        }
        return commit
    }
}
