package de.hoennig.gittally.git

import de.hoennig.gittally.config.ConfigLoader
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

@Service
class GitService(
    private val runner: GitCommandRunner,
    private val configLoader: ConfigLoader,
) {
    fun getTopLevel(workingDir: Path = Paths.get(".")): Path {
        val result = runner.run(listOf("git", "rev-parse", "--show-toplevel"), workingDir)
        if (!result.isSuccess) {
            throw RuntimeException("Not a git repository")
        }
        return Paths.get(result.stdout.trim())
    }

    fun getOriginUrl(workingDir: Path = Paths.get(".")): String? {
        val result = runner.run(listOf("git", "remote", "get-url", "origin"), workingDir)
        return if (result.isSuccess) result.stdout.trim() else null
    }

    fun fetchOrigin(workingDir: Path = Paths.get(".")) {
        authenticated(workingDir) { environment ->
            runner.runOrThrow(listOf("git", "fetch", "--prune", "origin"), workingDir, environment)
        }
    }

    fun fetchBranch(
        branch: String,
        workingDir: Path = Paths.get("."),
    ) {
        authenticated(workingDir) { environment ->
            runner.runOrThrow(listOf("git", "fetch", "origin", branch), workingDir, environment)
        }
    }

    fun localBranches(workingDir: Path = Paths.get(".")): List<String> =
        runner
            .runOrThrow(listOf("git", "for-each-ref", "--format=%(refname:strip=2)", "refs/heads"), workingDir)
            .lines()

    fun originBranches(workingDir: Path = Paths.get(".")): List<String> =
        runner
            .runOrThrow(listOf("git", "for-each-ref", "--format=%(refname:strip=3)", "refs/remotes/origin"), workingDir)
            .lines()
            .filter { it != "HEAD" }

    /**
     * A branch has new commits when its origin counterpart is ahead of the local branch,
     * or when it exists only on origin.
     */
    fun hasNewCommits(
        branch: String,
        workingDir: Path = Paths.get("."),
    ): Boolean {
        val originRef = "refs/remotes/origin/$branch"
        if (!refExists("refs/heads/$branch", workingDir)) {
            return refExists(originRef, workingDir)
        }
        val upstream =
            runner
                .runOrThrow(listOf("git", "for-each-ref", "--format=%(upstream)", "refs/heads/$branch"), workingDir)
                .stdout
                .trim()
        val compareTo =
            when {
                upstream.isNotEmpty() -> upstream
                refExists(originRef, workingDir) -> originRef
                else -> return false
            }
        if (!refExists(compareTo, workingDir)) {
            return false
        }
        val count =
            runner
                .runOrThrow(listOf("git", "rev-list", "--count", "refs/heads/$branch..$compareTo"), workingDir)
                .stdout
                .trim()
        return count.toLong() > 0
    }

    /** Origin branches without a local counterpart whose latest commit is younger than [maxAge]. */
    fun newOriginBranches(
        maxAge: Duration,
        workingDir: Path = Paths.get("."),
    ): List<String> {
        val cutoff = Instant.now().minus(maxAge)
        val local = localBranches(workingDir).toSet()
        return runner
            .runOrThrow(
                listOf(
                    "git",
                    "for-each-ref",
                    "--sort=-committerdate",
                    "--format=%(refname:strip=3) %(committerdate:unix)",
                    "refs/remotes/origin",
                ),
                workingDir,
            ).lines()
            .mapNotNull { line ->
                val branch = line.substringBeforeLast(' ')
                val epochSeconds = line.substringAfterLast(' ').toLongOrNull() ?: return@mapNotNull null
                branch to Instant.ofEpochSecond(epochSeconds)
            }.filter { (branch, committedAt) ->
                branch != "HEAD" && branch !in local && committedAt >= cutoff
            }.map { (branch, _) -> branch }
    }

    /** Switches to an existing local branch, or creates a tracking branch from origin. */
    fun checkout(
        branch: String,
        workingDir: Path = Paths.get("."),
    ) {
        if (refExists("refs/heads/$branch", workingDir)) {
            runner.runOrThrow(listOf("git", "switch", branch), workingDir)
        } else {
            runner.runOrThrow(listOf("git", "switch", "--track", "-c", branch, "refs/remotes/origin/$branch"), workingDir)
        }
    }

    fun resetHardToOrigin(
        branch: String,
        workingDir: Path = Paths.get("."),
    ) {
        runner.runOrThrow(listOf("git", "reset", "--hard", "origin/$branch"), workingDir)
    }

    fun commitTimestamp(
        sha: String,
        workingDir: Path = Paths.get("."),
    ): Instant =
        OffsetDateTime
            .parse(
                runner
                    .runOrThrow(listOf("git", "show", "-s", "--format=%cI", sha), workingDir)
                    .stdout
                    .trim(),
            ).toInstant()

    fun currentBranch(workingDir: Path = Paths.get(".")): String? =
        runner
            .runOrThrow(listOf("git", "branch", "--show-current"), workingDir)
            .stdout
            .trim()
            .ifEmpty { null }

    fun headCommit(workingDir: Path = Paths.get(".")): String =
        runner
            .runOrThrow(listOf("git", "rev-parse", "HEAD"), workingDir)
            .stdout
            .trim()

    /** Creates a worktree at [path] with [commit] checked out as a detached HEAD; [path] must not exist yet. */
    fun worktreeAdd(
        path: Path,
        commit: String,
        workingDir: Path = Paths.get("."),
    ) {
        runner.runOrThrow(listOf("git", "worktree", "add", "--detach", path.toString(), commit), workingDir)
    }

    /** Removes registrations of worktrees whose directories no longer exist. */
    fun worktreePrune(workingDir: Path = Paths.get(".")) {
        runner.runOrThrow(listOf("git", "worktree", "prune"), workingDir)
    }

    /** Checks out [commit] as a detached HEAD, discarding local modifications to tracked files. */
    fun checkoutDetached(
        commit: String,
        workingDir: Path = Paths.get("."),
    ) {
        runner.runOrThrow(listOf("git", "checkout", "--force", "--detach", commit), workingDir)
    }

    private fun refExists(
        ref: String,
        workingDir: Path,
    ): Boolean = runner.run(listOf("git", "show-ref", "--quiet", "--verify", ref), workingDir).isSuccess

    /**
     * Runs [block] with askpass credentials from config for HTTPS origins.
     * SSH origins and missing credentials run without auth setup;
     * terminal prompts are always disabled so a fetch can never hang on stdin.
     */
    private fun <T> authenticated(
        workingDir: Path,
        block: (environment: Map<String, String>) -> T,
    ): T {
        val noPrompt = mapOf("GIT_TERMINAL_PROMPT" to "0")
        if (getOriginUrl(workingDir)?.startsWith("http") != true) {
            return block(noPrompt)
        }
        val git = configLoader.load(getTopLevel(workingDir)).git
        if (git.account.isBlank() || git.token.isBlank()) {
            return block(noPrompt)
        }
        return GitAskPass.withAskPass(git.account, git.token, block)
    }
}
