package de.hoennig.werkator.commands

import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoRegistry
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
    private val other = mockk<BuildResultRepository>()
    private val registry =
        mockk<RepoRegistry>().also {
            val current =
                RepoContext(
                    "current",
                    java.nio.file.Paths
                        .get("."),
                    repository,
                    mockk(),
                )
            val second =
                RepoContext(
                    "second",
                    java.nio.file.Paths
                        .get("second"),
                    other,
                    mockk(),
                )
            every { it.current() } returns current
            every { it.all() } returns listOf(current, second)
            every { it.byName("second") } returns second
            every { it.byName("nope") } returns null
        }

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
            clearMocks(repository, other)
        }

        test("--repo selects a registered repository; an unknown name is a usage error naming the registered ones") {
            every { other.latestPerName() } returns listOf(result("main", BuildStatus.SUCCESS))

            var exitCode = -1
            val console = captureConsole { exitCode = StatusCommand(registry).apply { repoOption.name = "second" }.call() }
            exitCode shouldBe 0
            console.stdout shouldContain "main"
            verify(exactly = 0) { repository.latestPerName() }

            val failed = captureConsole { exitCode = StatusCommand(registry).apply { repoOption.name = "nope" }.call() }
            exitCode shouldBe 2
            failed.stderr shouldContain "current, second"
        }

        test("prints the latest build per branch as a table with short commits and legacy duration format") {
            every { repository.latestPerName() } returns
                listOf(
                    result("main", BuildStatus.SUCCESS),
                    result("feature/x", BuildStatus.FAILED, duration = null),
                )

            var exitCode = -1
            val console = captureConsole { exitCode = StatusCommand(registry).call() }

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

            val command = StatusCommand(registry).apply { history = true }
            var exitCode = -1
            val console = captureConsole { exitCode = command.call() }

            exitCode shouldBe 0
            console.stdout shouldContain "success"
            console.stdout shouldContain "failed"
            verify { repository.history() }
        }

        test("prints a hint when no builds are recorded yet") {
            every { repository.latestPerName() } returns emptyList()

            var exitCode = -1
            val console = captureConsole { exitCode = StatusCommand(registry).call() }

            exitCode shouldBe 0
            console.stdout shouldContain "(no builds recorded)"
        }
    }
}
