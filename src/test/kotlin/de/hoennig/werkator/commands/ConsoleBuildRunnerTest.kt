package de.hoennig.werkator.commands

import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.build.RunningBuild
import de.hoennig.werkator.repo.RepoContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class ConsoleBuildRunnerTest : FunSpec() {
    private val buildExecutor = mockk<BuildExecutor>()
    private val repository = mockk<BuildResultRepository>()
    private val artifactStore = mockk<ArtifactStore>()

    private lateinit var tempDir: Path
    private lateinit var repo: RepoContext

    private fun runner() =
        ConsoleBuildRunner(buildExecutor).apply {
            pollIntervalMillis = 1
            persistTimeoutMillis = 100
        }

    private fun runningBuild(stagingDir: Path) =
        RunningBuild(
            branch = "main",
            commit = "0123456789abcdef",
            artifactKey = "main-key",
            startedAt = Instant.parse("2026-07-07T10:00:00Z"),
            stagingDir = stagingDir,
            liveLogFile = stagingDir.resolve(BuildExecutor.LIVE_LOG_FILE),
        )

    private fun result(
        status: BuildStatus,
        duration: Duration? = Duration.ofSeconds(83),
    ) = BuildResult(
        branch = "main",
        commit = "0123456789abcdef",
        status = status,
        startedAt = Instant.parse("2026-07-07T10:00:00Z"),
        duration = duration,
        artifactKey = "main-key",
    )

    init {
        beforeEach {
            clearMocks(buildExecutor, repository, artifactStore)
            tempDir = Files.createTempDirectory("werkator-console-build-test")
            repo = RepoContext("test", tempDir, repository, artifactStore)
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        test("streams the live log and reports the final status once the build is terminal") {
            val stagingDir = Files.createDirectory(tempDir.resolve("staging"))
            val build = runningBuild(stagingDir)
            Files.writeString(build.liveLogFile, "compiling ...\ntests green\n")
            every { buildExecutor.startBuild(repo, "main", "0123456789abcdef") } returns build
            // the terminal status arrives together with the finished persist (staging gone)
            every { repository.history() } answers {
                stagingDir.toFile().deleteRecursively()
                listOf(result(BuildStatus.SUCCESS))
            }
            every { artifactStore.artifactDir("main-key") } returns null

            var status: BuildStatus? = null
            val console = captureConsole { status = runner().buildAndStream(repo, "main", "0123456789abcdef") }

            status shouldBe BuildStatus.SUCCESS
            console.stdout shouldContain "compiling ...\ntests green\n"
            console.stdout shouldContain "build of branch main: success after 1:23"
        }

        test("drains the rest of the log from the persisted copy after the staging directory is gone") {
            val stagingDir = tempDir.resolve("staging-never-created")
            val build = runningBuild(stagingDir)
            val persistedDir = Files.createDirectory(tempDir.resolve("persisted"))
            Files.writeString(persistedDir.resolve(BuildExecutor.LIVE_LOG_FILE), "full build output\n")
            every { buildExecutor.startBuild(repo, "main", "0123456789abcdef") } returns build
            every { repository.history() } returns listOf(result(BuildStatus.FAILED))
            every { artifactStore.artifactDir("main-key") } returns persistedDir

            var status: BuildStatus? = null
            val console = captureConsole { status = runner().buildAndStream(repo, "main", "0123456789abcdef") }

            status shouldBe BuildStatus.FAILED
            console.stdout shouldContain "full build output"
            console.stdout shouldContain "build of branch main: failed after 1:23"
        }

        test("a staging directory that never gets persisted only warns after the timeout") {
            val stagingDir = Files.createDirectory(tempDir.resolve("staging"))
            val build = runningBuild(stagingDir)
            Files.writeString(build.liveLogFile, "some output\n")
            every { buildExecutor.startBuild(repo, "main", "0123456789abcdef") } returns build
            every { repository.history() } returns listOf(result(BuildStatus.SUCCESS, duration = null))

            var status: BuildStatus? = null
            val console = captureConsole { status = runner().buildAndStream(repo, "main", "0123456789abcdef") }

            status shouldBe BuildStatus.SUCCESS
            console.stdout shouldContain "some output"
            console.stdout shouldContain "build of branch main: success"
            console.stderr shouldContain "were not persisted"
        }
    }
}
