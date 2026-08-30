package de.hoennig.werkator.build

import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.git.GitCommandRunner
import de.hoennig.werkator.git.GitService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path

/** Integration tests against a local fixture repository; no network access needed. */
class GitWorktreeWorkspacesTest : FunSpec() {
    private val runner = GitCommandRunner()
    private val gitService = GitService(runner, ConfigLoader())
    private val workspaces = GitWorktreeWorkspaces(gitService)

    // hermetic git: fixed identity, no user/system config (hooks, gpg signing, ...)
    private val gitEnvironment =
        mapOf(
            "GIT_AUTHOR_NAME" to "Werkator Test",
            "GIT_AUTHOR_EMAIL" to "test@example.com",
            "GIT_COMMITTER_NAME" to "Werkator Test",
            "GIT_COMMITTER_EMAIL" to "test@example.com",
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
        )

    private inner class Fixture {
        val repo: Path = Files.createTempDirectory("werkator-workspaces-test").resolve("repo")

        init {
            Files.createDirectories(repo)
            git("init", "-b", "main", ".")
            commitFile("README.md", "hello")
        }

        fun git(vararg args: String) {
            runner.runOrThrow(listOf("git") + args, repo, gitEnvironment)
        }

        fun commitFile(
            name: String,
            content: String,
        ): String {
            Files.writeString(repo.resolve(name), content)
            git("add", name)
            git("commit", "-m", "add $name")
            return gitService.headCommit(repo)
        }
    }

    init {
        test("creates a per-branch worktree with the commit checked out detached") {
            val fixture = Fixture()
            val commit = gitService.headCommit(fixture.repo)

            val workspace = workspaces.prepare("main", commit, fixture.repo)

            workspace.toString() shouldStartWith
                fixture.repo.resolve(GitWorktreeWorkspaces.WORKTREES_DIR).toString()
            workspace.fileName.toString() shouldBe ArtifactKeys.branchKey("main")
            Files.readString(workspace.resolve("README.md")) shouldBe "hello"
            gitService.headCommit(workspace) shouldBe commit
            gitService.currentBranch(workspace).shouldBeNull()
        }

        test("reuses the worktree and switches it to a newer commit") {
            val fixture = Fixture()
            val firstCommit = gitService.headCommit(fixture.repo)
            val firstWorkspace = workspaces.prepare("main", firstCommit, fixture.repo)
            val secondCommit = fixture.commitFile("second.txt", "second")

            val secondWorkspace = workspaces.prepare("main", secondCommit, fixture.repo)

            secondWorkspace shouldBe firstWorkspace
            gitService.headCommit(secondWorkspace) shouldBe secondCommit
            Files.readString(secondWorkspace.resolve("second.txt")) shouldBe "second"
        }

        test("recreates a workspace whose directory was deleted") {
            val fixture = Fixture()
            val commit = gitService.headCommit(fixture.repo)
            val workspace = workspaces.prepare("main", commit, fixture.repo)
            workspace.toFile().deleteRecursively()

            val recreated = workspaces.prepare("main", commit, fixture.repo)

            recreated shouldBe workspace
            gitService.headCommit(recreated) shouldBe commit
        }

        test("replaces a broken workspace directory that is not a worktree") {
            val fixture = Fixture()
            val commit = gitService.headCommit(fixture.repo)
            val workspace = fixture.repo.resolve(GitWorktreeWorkspaces.WORKTREES_DIR).resolve(ArtifactKeys.branchKey("main"))
            Files.createDirectories(workspace)
            Files.writeString(workspace.resolve("junk.txt"), "junk")

            val prepared = workspaces.prepare("main", commit, fixture.repo)

            prepared shouldBe workspace
            gitService.headCommit(prepared) shouldBe commit
            Files.exists(prepared.resolve("junk.txt")) shouldBe false
        }

        test("different branches get different workspaces") {
            val fixture = Fixture()
            val mainCommit = gitService.headCommit(fixture.repo)
            fixture.git("switch", "-c", "feature/x")
            val featureCommit = fixture.commitFile("feature.txt", "feature")
            fixture.git("switch", "main")

            val mainWorkspace = workspaces.prepare("main", mainCommit, fixture.repo)
            val featureWorkspace = workspaces.prepare("feature/x", featureCommit, fixture.repo)

            featureWorkspace shouldNotBe mainWorkspace
            gitService.headCommit(mainWorkspace) shouldBe mainCommit
            gitService.headCommit(featureWorkspace) shouldBe featureCommit
            Files.exists(mainWorkspace.resolve("feature.txt")) shouldBe false
            Files.readString(featureWorkspace.resolve("feature.txt")) shouldBe "feature"
        }
    }
}
