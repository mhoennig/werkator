package de.hoennig.werkator.git

import de.hoennig.werkator.config.ConfigLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Integration tests against local fixture repositories:
 * a bare "origin", a "seed" clone that pushes to it, and a "work" clone under test.
 * No network access needed.
 */
class GitServiceTest : FunSpec() {
    private val runner = GitCommandRunner()
    private val service = GitService(runner, ConfigLoader())

    // hermetic git: fixed identity, no user/system config (hooks, gpg signing, ...)
    private val gitEnvironment =
        mapOf(
            "GIT_AUTHOR_NAME" to "werkator Test",
            "GIT_AUTHOR_EMAIL" to "test@example.com",
            "GIT_COMMITTER_NAME" to "werkator Test",
            "GIT_COMMITTER_EMAIL" to "test@example.com",
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
        )

    private inner class Fixture {
        val root: Path = Files.createTempDirectory("werkator-git-test")
        val origin: Path = root.resolve("origin.git")
        val seed: Path = root.resolve("seed")
        val work: Path = root.resolve("work")

        init {
            git(root, "init", "--bare", "-b", "main", "origin.git")
            git(root, "init", "-b", "main", "seed")
            commitFile(seed, "README.md", "hello")
            git(seed, "remote", "add", "origin", origin.toString())
            git(seed, "push", "origin", "main")
            git(root, "clone", origin.toString(), "work")
        }

        fun git(
            dir: Path,
            vararg args: String,
            environment: Map<String, String> = emptyMap(),
        ): GitCommandResult = runner.runOrThrow(listOf("git") + args, dir, gitEnvironment + environment)

        fun commitFile(
            dir: Path,
            name: String,
            content: String,
            environment: Map<String, String> = emptyMap(),
        ) {
            Files.writeString(dir.resolve(name), content)
            git(dir, "add", name)
            git(dir, "commit", "-m", "add $name", environment = environment)
        }

        /** Creates a branch with one commit in the seed clone and pushes it to origin. */
        fun pushNewSeedBranch(
            branch: String,
            environment: Map<String, String> = emptyMap(),
        ) {
            git(seed, "switch", "-c", branch)
            commitFile(seed, branch.replace('/', '-') + ".txt", branch, environment)
            git(seed, "push", "origin", branch)
            git(seed, "switch", "main")
        }
    }

    init {
        test("getTopLevel returns the repo root from a subdirectory") {
            val fixture = Fixture()
            val subDir = Files.createDirectories(fixture.work.resolve("sub/dir"))

            service.getTopLevel(subDir).toRealPath() shouldBe fixture.work.toRealPath()
        }

        test("getOriginUrl returns the origin URL, or null without an origin") {
            val fixture = Fixture()
            fixture.git(fixture.root, "init", "-b", "main", "plain")

            service.getOriginUrl(fixture.work) shouldBe fixture.origin.toString()
            service.getOriginUrl(fixture.root.resolve("plain")).shouldBeNull()
        }

        test("localBranches and originBranches list branches, without HEAD") {
            val fixture = Fixture()

            service.localBranches(fixture.work) shouldContainExactly listOf("main")
            service.originBranches(fixture.work) shouldContainExactly listOf("main")
        }

        test("originBranchHeads maps every origin branch to its head commit, without HEAD") {
            val fixture = Fixture()
            fixture.pushNewSeedBranch("feature/x")
            service.fetchOrigin(fixture.work)

            val heads = service.originBranchHeads(fixture.work)

            heads.keys shouldBe setOf("feature/x", "main")
            heads["main"] shouldBe fixture.git(fixture.work, "rev-parse", "refs/remotes/origin/main").stdout.trim()
            heads["feature/x"] shouldBe fixture.git(fixture.work, "rev-parse", "refs/remotes/origin/feature/x").stdout.trim()
        }

        test("pullRequestHeads returns the head commits of the remote's pull-request refs") {
            val fixture = Fixture()
            fixture.pushNewSeedBranch("feature/x")

            service.pullRequestHeads(fixture.work) shouldBe emptySet()

            // Gitea materializes a pull request as refs/pull/<n>/head on the served repository
            fixture.git(fixture.seed, "push", "origin", "refs/heads/feature/x:refs/pull/1/head")
            val featureHead = fixture.git(fixture.seed, "rev-parse", "refs/heads/feature/x").stdout.trim()

            service.pullRequestHeads(fixture.work) shouldBe setOf(featureHead)
        }

        test("fetchOrigin picks up new origin branches and prunes deleted ones") {
            val fixture = Fixture()
            fixture.pushNewSeedBranch("feature/x")

            service.fetchOrigin(fixture.work)
            service.originBranches(fixture.work) shouldContainExactly listOf("feature/x", "main")

            fixture.git(fixture.seed, "push", "origin", "--delete", "feature/x")
            service.fetchOrigin(fixture.work)
            service.originBranches(fixture.work) shouldContainExactly listOf("main")
        }

        test("hasNewCommits is false when the branch is up to date") {
            val fixture = Fixture()

            service.hasNewCommits("main", fixture.work).shouldBeFalse()
        }

        test("hasNewCommits is true after new commits arrived on origin") {
            val fixture = Fixture()
            fixture.commitFile(fixture.seed, "change.txt", "change")
            fixture.git(fixture.seed, "push", "origin", "main")

            service.fetchBranch("main", fixture.work)

            service.hasNewCommits("main", fixture.work).shouldBeTrue()
        }

        test("hasNewCommits is true for a branch that only exists on origin") {
            val fixture = Fixture()
            fixture.pushNewSeedBranch("feature/x")
            service.fetchOrigin(fixture.work)

            service.hasNewCommits("feature/x", fixture.work).shouldBeTrue()
        }

        test("hasNewCommits is false for an unknown branch") {
            val fixture = Fixture()

            service.hasNewCommits("no-such-branch", fixture.work).shouldBeFalse()
        }

        test("fastForwardLocalBranches advances the checked-out branch and its working tree") {
            val fixture = Fixture()
            fixture.commitFile(fixture.seed, "change.txt", "change")
            fixture.git(fixture.seed, "push", "origin", "main")
            service.fetchOrigin(fixture.work)

            service.fastForwardLocalBranches(fixture.work) shouldContainExactly listOf("main")

            service.localHeadCommit("main", fixture.work) shouldBe service.originHeadCommit("main", fixture.work)
            Files.readString(fixture.work.resolve("change.txt")) shouldBe "change"
            service.hasNewCommits("main", fixture.work).shouldBeFalse()
        }

        test("fastForwardLocalBranches advances a branch that is not checked out") {
            val fixture = Fixture()
            fixture.pushNewSeedBranch("feature/x")
            service.fetchOrigin(fixture.work)
            fixture.git(fixture.work, "branch", "feature/x", "refs/remotes/origin/feature/x")
            fixture.git(fixture.seed, "switch", "feature/x")
            fixture.commitFile(fixture.seed, "more.txt", "more")
            fixture.git(fixture.seed, "push", "origin", "feature/x")
            fixture.git(fixture.seed, "switch", "main")
            service.fetchOrigin(fixture.work)

            service.fastForwardLocalBranches(fixture.work) shouldContainExactly listOf("feature/x")

            service.localHeadCommit("feature/x", fixture.work) shouldBe
                service.originHeadCommit("feature/x", fixture.work)
        }

        test("fastForwardLocalBranches leaves a diverged local branch untouched") {
            val fixture = Fixture()
            fixture.commitFile(fixture.work, "local.txt", "local only")
            val localHead = service.localHeadCommit("main", fixture.work)
            fixture.commitFile(fixture.seed, "remote.txt", "remote only")
            fixture.git(fixture.seed, "push", "origin", "main")
            service.fetchOrigin(fixture.work)

            service.fastForwardLocalBranches(fixture.work).shouldBeEmpty()

            service.localHeadCommit("main", fixture.work) shouldBe localHead
        }

        test("fastForwardLocalBranches ignores branches without an origin counterpart") {
            val fixture = Fixture()
            fixture.git(fixture.work, "branch", "local-only")

            service.fastForwardLocalBranches(fixture.work).shouldBeEmpty()
        }

        test("newOriginBranches lists recent origin-only branches") {
            val fixture = Fixture()
            fixture.pushNewSeedBranch("feature/x")
            service.fetchOrigin(fixture.work)

            val branches = service.newOriginBranches(Duration.ofDays(5), fixture.work)

            branches shouldContainExactly listOf("feature/x")
        }

        test("newOriginBranches skips branches whose latest commit is older than maxAge") {
            val fixture = Fixture()
            val oldDate = mapOf("GIT_AUTHOR_DATE" to "2020-01-01T12:00:00+00:00", "GIT_COMMITTER_DATE" to "2020-01-01T12:00:00+00:00")
            fixture.pushNewSeedBranch("stale", environment = oldDate)
            service.fetchOrigin(fixture.work)

            service.newOriginBranches(Duration.ofDays(5), fixture.work).shouldNotContain("stale")
        }

        test("checkout switches to an existing local branch") {
            val fixture = Fixture()
            fixture.git(fixture.work, "switch", "-c", "local-branch")
            fixture.git(fixture.work, "switch", "main")

            service.checkout("local-branch", fixture.work)

            service.currentBranch(fixture.work) shouldBe "local-branch"
        }

        test("checkout creates a tracking branch from origin") {
            val fixture = Fixture()
            fixture.pushNewSeedBranch("feature/x")
            service.fetchOrigin(fixture.work)

            service.checkout("feature/x", fixture.work)

            service.currentBranch(fixture.work) shouldBe "feature/x"
            val upstream = fixture.git(fixture.work, "for-each-ref", "--format=%(upstream:short)", "refs/heads/feature/x")
            upstream.stdout.trim() shouldBe "origin/feature/x"
        }

        test("resetHardToOrigin discards local commits") {
            val fixture = Fixture()
            fixture.commitFile(fixture.work, "local.txt", "local only")
            val originHead = fixture.git(fixture.work, "rev-parse", "origin/main").stdout.trim()

            service.resetHardToOrigin("main", fixture.work)

            service.headCommit(fixture.work) shouldBe originHead
        }

        test("commitTimestamp returns the committer timestamp") {
            val fixture = Fixture()
            val date = mapOf("GIT_AUTHOR_DATE" to "2024-05-01T10:00:00+00:00", "GIT_COMMITTER_DATE" to "2024-05-01T10:00:00+00:00")
            fixture.commitFile(fixture.work, "dated.txt", "dated", environment = date)

            val timestamp = service.commitTimestamp(service.headCommit(fixture.work), fixture.work)

            timestamp shouldBe Instant.parse("2024-05-01T10:00:00Z")
        }

        test("currentBranch returns null when HEAD is detached") {
            val fixture = Fixture()
            fixture.git(fixture.work, "checkout", "--detach")

            service.currentBranch(fixture.work).shouldBeNull()
        }

        test("headCommit returns the full commit hash") {
            val fixture = Fixture()

            service.headCommit(fixture.work) shouldMatch Regex("[0-9a-f]{40}")
        }

        test("localHeadCommit returns the local branch head, or null for an unknown branch") {
            val fixture = Fixture()

            service.localHeadCommit("main", fixture.work) shouldBe service.headCommit(fixture.work)
            service.localHeadCommit("no-such-branch", fixture.work).shouldBeNull()
        }

        test("originHeadCommit returns the origin branch head, or null for an unknown branch") {
            val fixture = Fixture()

            service.originHeadCommit("main", fixture.work) shouldBe service.headCommit(fixture.work)
            service.originHeadCommit("no-such-branch", fixture.work).shouldBeNull()
        }

        test("worktreeAdd creates a detached worktree at the commit") {
            val fixture = Fixture()
            val head = service.headCommit(fixture.work)
            val worktree = fixture.work.resolve(".git/werkator/worktrees/main-test")

            service.worktreeAdd(worktree, head, fixture.work)

            Files.readString(worktree.resolve("README.md")) shouldBe "hello"
            service.headCommit(worktree) shouldBe head
            service.currentBranch(worktree).shouldBeNull()
        }

        test("checkoutDetached switches a worktree to another commit and discards local modifications") {
            val fixture = Fixture()
            val firstCommit = service.headCommit(fixture.work)
            fixture.commitFile(fixture.work, "second.txt", "second")
            val secondCommit = service.headCommit(fixture.work)
            val worktree = fixture.work.resolve(".git/werkator/worktrees/main-test")
            service.worktreeAdd(worktree, firstCommit, fixture.work)
            Files.writeString(worktree.resolve("README.md"), "dirty")

            service.checkoutDetached(secondCommit, worktree)

            service.headCommit(worktree) shouldBe secondCommit
            Files.readString(worktree.resolve("README.md")) shouldBe "hello"
            Files.readString(worktree.resolve("second.txt")) shouldBe "second"
        }

        test("worktreePrune allows re-adding a worktree whose directory was deleted") {
            val fixture = Fixture()
            val head = service.headCommit(fixture.work)
            val worktree = fixture.work.resolve(".git/werkator/worktrees/main-test")
            service.worktreeAdd(worktree, head, fixture.work)
            worktree.toFile().deleteRecursively()

            service.worktreePrune(fixture.work)

            service.worktreeAdd(worktree, head, fixture.work)
            service.headCommit(worktree) shouldBe head
        }
    }
}
