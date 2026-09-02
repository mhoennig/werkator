package de.hoennig.werkator.commands

import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import java.nio.file.Paths

class BuildCommandTest : FunSpec() {
    private val gitService = mockk<GitService>()
    private val consoleBuildRunner = mockk<ConsoleBuildRunner>()
    private val dir: Path = Paths.get(".")
    private val repo = RepoContext("test", dir, mockk(), mockk())

    private fun command(fragment: String? = null) =
        BuildCommand(gitService, consoleBuildRunner, repo).apply {
            branchFragment = fragment
        }

    init {
        beforeEach {
            clearMocks(gitService, consoleBuildRunner)
            justRun { gitService.fetchOrigin(dir) }
        }

        test("builds the current branch at its local head when no branch is given") {
            every { gitService.currentBranch(dir) } returns "main"
            every { gitService.localHeadCommit("main", dir) } returns "local-head"
            every { gitService.hasNewCommits("main", dir) } returns false
            every { consoleBuildRunner.buildAndStream(repo, "main", "local-head") } returns BuildStatus.SUCCESS

            var exitCode = -1
            captureConsole { exitCode = command().call() }

            exitCode shouldBe 0
            verify { consoleBuildRunner.buildAndStream(repo, "main", "local-head") }
        }

        test("builds origin's head when the branch has new commits on origin") {
            every { gitService.currentBranch(dir) } returns "main"
            every { gitService.localHeadCommit("main", dir) } returns "local-head"
            every { gitService.hasNewCommits("main", dir) } returns true
            every { gitService.originHeadCommit("main", dir) } returns "origin-head"
            every { consoleBuildRunner.buildAndStream(repo, "main", "origin-head") } returns BuildStatus.SUCCESS

            var exitCode = -1
            captureConsole { exitCode = command().call() }

            exitCode shouldBe 0
            verify { consoleBuildRunner.buildAndStream(repo, "main", "origin-head") }
        }

        test("a failing build exits with code 1") {
            every { gitService.currentBranch(dir) } returns "main"
            every { gitService.localHeadCommit("main", dir) } returns "local-head"
            every { gitService.hasNewCommits("main", dir) } returns false
            every { consoleBuildRunner.buildAndStream(repo, "main", "local-head") } returns BuildStatus.FAILED

            var exitCode = -1
            captureConsole { exitCode = command().call() }

            exitCode shouldBe 1
        }

        test("resolves a unique branch-name fragment against local and origin branches") {
            every { gitService.localBranches(dir) } returns listOf("main")
            every { gitService.originBranches(dir) } returns listOf("main", "feature/x")
            every { gitService.localHeadCommit("feature/x", dir) } returns null
            every { gitService.originHeadCommit("feature/x", dir) } returns "origin-head"
            every { consoleBuildRunner.buildAndStream(repo, "feature/x", "origin-head") } returns BuildStatus.SUCCESS

            var exitCode = -1
            captureConsole { exitCode = command(fragment = "x").call() }

            exitCode shouldBe 0
            verify { consoleBuildRunner.buildAndStream(repo, "feature/x", "origin-head") }
        }

        test("an ambiguous fragment lists the candidates and exits with code 2") {
            every { gitService.localBranches(dir) } returns listOf("feature/login")
            every { gitService.originBranches(dir) } returns listOf("feature/logout")

            var exitCode = -1
            val console = captureConsole { exitCode = command(fragment = "feature").call() }

            exitCode shouldBe 2
            console.stderr shouldContain "multiple branches match 'feature'"
            console.stderr shouldContain "feature/login"
            console.stderr shouldContain "feature/logout"
            verify { consoleBuildRunner wasNot Called }
        }

        test("a fragment without any match exits with code 2") {
            every { gitService.localBranches(dir) } returns listOf("main")
            every { gitService.originBranches(dir) } returns listOf("main")

            var exitCode = -1
            val console = captureConsole { exitCode = command(fragment = "release").call() }

            exitCode shouldBe 2
            console.stderr shouldContain "no local or origin branch matches 'release'"
            verify { consoleBuildRunner wasNot Called }
        }

        test("a detached HEAD without a branch argument exits with code 2") {
            every { gitService.currentBranch(dir) } returns null

            var exitCode = -1
            val console = captureConsole { exitCode = command().call() }

            exitCode shouldBe 2
            console.stderr shouldContain "HEAD is detached"
            verify { consoleBuildRunner wasNot Called }
        }

        test("a failed fetch only warns and the build continues from the last known origin state") {
            every { gitService.fetchOrigin(dir) } throws RuntimeException("origin unreachable")
            every { gitService.currentBranch(dir) } returns "main"
            every { gitService.localHeadCommit("main", dir) } returns "local-head"
            every { gitService.hasNewCommits("main", dir) } returns false
            every { consoleBuildRunner.buildAndStream(repo, "main", "local-head") } returns BuildStatus.SUCCESS

            var exitCode = -1
            val console = captureConsole { exitCode = command().call() }

            exitCode shouldBe 0
            console.stderr shouldContain "warning: fetching origin failed"
        }
    }
}
