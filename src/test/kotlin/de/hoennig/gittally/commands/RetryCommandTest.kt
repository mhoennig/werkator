package de.hoennig.gittally.commands

import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.git.GitService
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
import java.time.Instant

class RetryCommandTest : FunSpec() {
    private val gitService = mockk<GitService>()
    private val repository = mockk<BuildResultRepository>()
    private val consoleBuildRunner = mockk<ConsoleBuildRunner>()
    private val dir: Path = Paths.get(".")

    private fun command() = RetryCommand(gitService, repository, consoleBuildRunner).apply { workingDir = dir }

    private fun result(
        branch: String,
        status: BuildStatus,
    ) = BuildResult(
        branch = branch,
        commit = "commit-$branch",
        status = status,
        startedAt = Instant.parse("2026-07-07T10:00:00Z"),
        duration = null,
        artifactKey = "$branch-key",
    )

    init {
        beforeEach {
            clearMocks(gitService, repository, consoleBuildRunner)
            justRun { gitService.fetchOrigin(dir) }
        }

        test("retries every branch whose latest build failed, but no others") {
            every { repository.latestPerBranch() } returns
                listOf(
                    result("main", BuildStatus.FAILED),
                    result("feature/ok", BuildStatus.SUCCESS),
                    result("feature/y", BuildStatus.FAILED),
                )
            every { gitService.originHeadCommit("main", dir) } returns "head-main"
            every { gitService.originHeadCommit("feature/y", dir) } returns "head-y"
            every { consoleBuildRunner.buildAndStream(any(), any(), dir) } returns BuildStatus.SUCCESS

            var exitCode = -1
            captureConsole { exitCode = command().call() }

            exitCode shouldBe 0
            verify { consoleBuildRunner.buildAndStream("main", "head-main", dir) }
            verify { consoleBuildRunner.buildAndStream("feature/y", "head-y", dir) }
            verify(exactly = 0) { consoleBuildRunner.buildAndStream("feature/ok", any(), dir) }
        }

        test("exits with code 1 when a retried build fails again") {
            every { repository.latestPerBranch() } returns listOf(result("main", BuildStatus.FAILED))
            every { gitService.originHeadCommit("main", dir) } returns "head-main"
            every { consoleBuildRunner.buildAndStream("main", "head-main", dir) } returns BuildStatus.FAILED

            var exitCode = -1
            captureConsole { exitCode = command().call() }

            exitCode shouldBe 1
        }

        test("skips failed branches that are gone from origin") {
            every { repository.latestPerBranch() } returns listOf(result("gone", BuildStatus.FAILED))
            every { gitService.originHeadCommit("gone", dir) } returns null

            var exitCode = -1
            val console = captureConsole { exitCode = command().call() }

            exitCode shouldBe 0
            console.stdout shouldContain "skipping branch gone: gone from origin"
            verify { consoleBuildRunner wasNot Called }
        }

        test("prints a hint when there is nothing to retry") {
            every { repository.latestPerBranch() } returns
                listOf(
                    result("main", BuildStatus.SUCCESS),
                    result("feature/x", BuildStatus.INTERRUPTED),
                )

            var exitCode = -1
            val console = captureConsole { exitCode = command().call() }

            exitCode shouldBe 0
            console.stdout shouldContain "no failed builds to retry"
            verify { consoleBuildRunner wasNot Called }
        }
    }
}
