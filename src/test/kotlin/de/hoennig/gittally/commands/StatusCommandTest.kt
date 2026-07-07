package de.hoennig.gittally.commands

import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant

class StatusCommandTest : FunSpec() {
    private val repository = mockk<BuildResultRepository>()

    private fun result(
        branch: String,
        status: BuildStatus,
        duration: Duration? = Duration.ofSeconds(83),
    ) = BuildResult(
        branch = branch,
        commit = "0123456789abcdef0123456789abcdef01234567",
        status = status,
        startedAt = Instant.parse("2026-07-07T10:00:00Z"),
        duration = duration,
        artifactKey = "$branch-key",
    )

    init {
        beforeEach {
            clearMocks(repository)
        }

        test("prints the latest build per branch as a table with short commits and legacy duration format") {
            every { repository.latestPerBranch() } returns
                listOf(
                    result("main", BuildStatus.SUCCESS),
                    result("feature/x", BuildStatus.FAILED, duration = null),
                )

            var exitCode = -1
            val console = captureConsole { exitCode = StatusCommand(repository).call() }

            exitCode shouldBe 0
            console.stdout shouldContain "BRANCH"
            console.stdout shouldContain "DURATION"
            console.stdout shouldContain "main"
            console.stdout shouldContain "feature/x"
            console.stdout shouldContain "success"
            console.stdout shouldContain "failed"
            console.stdout shouldContain "0123456789ab"
            console.stdout shouldNotContain "0123456789abc"
            console.stdout shouldContain "1:23"
        }

        test("--history prints all recorded builds instead of only the latest per branch") {
            every { repository.history() } returns
                listOf(
                    result("main", BuildStatus.SUCCESS),
                    result("main", BuildStatus.FAILED),
                )

            val command = StatusCommand(repository).apply { history = true }
            var exitCode = -1
            val console = captureConsole { exitCode = command.call() }

            exitCode shouldBe 0
            console.stdout shouldContain "success"
            console.stdout shouldContain "failed"
            verify { repository.history() }
        }

        test("prints a hint when no builds are recorded yet") {
            every { repository.latestPerBranch() } returns emptyList()

            var exitCode = -1
            val console = captureConsole { exitCode = StatusCommand(repository).call() }

            exitCode shouldBe 0
            console.stdout shouldContain "(no builds recorded)"
        }
    }
}
