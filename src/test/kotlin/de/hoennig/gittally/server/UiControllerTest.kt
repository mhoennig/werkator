package de.hoennig.gittally.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildExecutor
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.build.RunningBuild
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.config.GitTallyConfig
import de.hoennig.gittally.config.GiteaConfig
import de.hoennig.gittally.config.ServerConfig
import de.hoennig.gittally.metrics.MetricAggregate
import de.hoennig.gittally.metrics.SystemMetrics
import de.hoennig.gittally.metrics.SystemMetricsCollector
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

@WebMvcTest(UiController::class, properties = ["spring.main.web-application-type=servlet"])
class UiControllerTest : FunSpec() {
    private val tempDir: Path = Files.createTempDirectory("gittally-ui-test")

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var repository: BuildResultRepository

    @MockkBean
    lateinit var buildExecutor: BuildExecutor

    @MockkBean
    lateinit var artifactStore: ArtifactStore

    @MockkBean
    lateinit var controlTokens: ControlTokenService

    @MockkBean
    lateinit var configLoader: ConfigLoader

    @MockkBean
    lateinit var metricsCollector: SystemMetricsCollector

    @MockkBean
    lateinit var branchListing: BranchListing

    @MockkBean
    lateinit var branchPermalinks: BranchPermalinks

    private val startedAt = Instant.parse("2026-07-07T10:00:00Z")

    private val emptySystemMetrics =
        SystemMetrics(
            timestamp = null,
            sampleCount = 0,
            cpuCount = 8,
            ramTotalGib = null,
            diskTotalGib = null,
            cpuUsed = null,
            cpuIdle = null,
            ramUsedGib = null,
            ramFreeGib = null,
            diskUsedGib = null,
            diskFreeGib = null,
            repoSizeGib = null,
        )

    private val successResult =
        BuildResult(
            branch = "main",
            commit = "0123456789abcdef0123456789abcdef01234567",
            status = BuildStatus.SUCCESS,
            startedAt = startedAt,
            duration = Duration.ofSeconds(83),
            artifactKey = "main-abc123-key",
        )

    init {
        beforeEach {
            clearMocks(
                repository,
                buildExecutor,
                artifactStore,
                controlTokens,
                configLoader,
                metricsCollector,
                branchListing,
                branchPermalinks,
            )
            every { configLoader.load(any()) } returns
                GitTallyConfig(
                    server = ServerConfig(impressumUrl = "https://example.org/imprint"),
                    gitea = GiteaConfig(baseUrl = "https://git.example.org", owner = "acme", repo = "widget"),
                )
            every { controlTokens.token() } returns "test-token"
        }

        test("latest view renders the empty state") {
            every { repository.latestPerBranch() } returns emptyList()

            mockMvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("No builds recorded yet.")))
                .andExpect(content().string(containsString("""data-api="/api/builds/latest"""")))
                .andExpect(content().string(containsString("""id="reload-button"""")))
        }

        test("latest view renders rows with badge, Gitea links, artifact link, actions, and token") {
            every { repository.latestPerBranch() } returns listOf(successResult)

            mockMvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("""status status-success""")))
                .andExpect(content().string(containsString("https://git.example.org/acme/widget/src/branch/main")))
                .andExpect(
                    content().string(containsString("https://git.example.org/acme/widget/commit/0123456789abcdef0123456789abcdef01234567")),
                ).andExpect(content().string(containsString("/builds/main-abc123-key")))
                .andExpect(content().string(containsString("""data-action="restart"""")))
                .andExpect(content().string(containsString("""data-action="delete"""")))
                .andExpect(content().string(containsString("""name="gittally-control-token" content="test-token"""")))
                .andExpect(content().string(containsString("https://example.org/imprint")))
                .andExpect(content().string(containsString("1:23")))
        }

        test("branches view renders built and never-built branches with restart actions") {
            every { branchListing.branches(any()) } returns
                listOf(
                    BranchDto.from("main", "ignored-head", successResult, hasGreenBuild = true),
                    BranchDto.from("feature/x", "fedcba9876543210fedcba9876543210fedcba98", null),
                )

            mockMvc
                .perform(get("/branches"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("status status-success")))
                .andExpect(content().string(containsString("status status-unknown")))
                .andExpect(content().string(containsString("feature/x")))
                .andExpect(content().string(containsString("fedcba987654")))
                .andExpect(content().string(containsString("""data-api="/api/branches"""")))
                .andExpect(content().string(containsString("""data-action="restart"""")))
                .andExpect(content().string(containsString("""href="/branches/main"""")))
                .andExpect(content().string(containsString("Permanent link")))
        }

        test("history view renders mixed history without restart actions") {
            every { repository.history() } returns
                listOf(
                    successResult.copy(branch = "main", status = BuildStatus.RUNNING, duration = null, artifactKey = "run-key"),
                    successResult,
                    successResult.copy(branch = "feature/x", status = BuildStatus.FAILED, artifactKey = "failed-key"),
                )

            mockMvc
                .perform(get("/history"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("status status-running")))
                .andExpect(content().string(containsString("status status-success")))
                .andExpect(content().string(containsString("status status-failed")))
                .andExpect(content().string(containsString("feature/x")))
                .andExpect(content().string(not(containsString("""data-action="restart""""))))
        }

        test("current view renders a card per running build with cancel button and started-at attribute") {
            val build =
                RunningBuild(
                    branch = "main",
                    commit = successResult.commit,
                    artifactKey = "main-abc123-running",
                    startedAt = startedAt,
                    stagingDir = tempDir,
                    liveLogFile = tempDir.resolve("build.log"),
                )
            every { buildExecutor.currentBuilds() } returns listOf(build)
            every { repository.history() } returns
                listOf(successResult.copy(status = BuildStatus.RUNNING, artifactKey = build.artifactKey, duration = null))

            mockMvc
                .perform(get("/current"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("build-card")))
                .andExpect(content().string(containsString("""data-started-at="2026-07-07T10:00:00Z"""")))
                .andExpect(content().string(containsString("""data-action="cancel"""")))
                .andExpect(content().string(containsString("status status-running")))
        }

        test("current view renders a clear no-build state") {
            every { buildExecutor.currentBuilds() } returns emptyList()
            every { repository.history() } returns emptyList()

            mockMvc
                .perform(get("/current"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("No build is currently running.")))
        }

        test("artifact index renders build command, log links, and topmost report index pages") {
            val artifactDir = Files.createDirectories(tempDir.resolve("main-abc123-key"))
            Files.writeString(artifactDir.resolve("build.stdout.log"), "out")
            Files.writeString(artifactDir.resolve("build.stderr.log"), "err")
            Files.createDirectories(artifactDir.resolve("reports/tests/test"))
            Files.writeString(artifactDir.resolve("reports/tests/test/index.html"), "<html></html>")
            Files.createDirectories(artifactDir.resolve("reports/tests/test/packages"))
            Files.writeString(artifactDir.resolve("reports/tests/test/packages/index.html"), "<html></html>")
            every { repository.history() } returns listOf(successResult)
            every { artifactStore.artifactDir("main-abc123-key") } returns artifactDir

            mockMvc
                .perform(get("/builds/main-abc123-key"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("./gradlew --console=plain --no-daemon test")))
                .andExpect(content().string(containsString("""/artifacts/main-abc123-key/build.stdout.log" target="_blank"""")))
                .andExpect(content().string(containsString("/artifacts/main-abc123-key/build.stderr.log")))
                .andExpect(
                    content().string(containsString("""/artifacts/main-abc123-key/reports/tests/test/index.html" target="_blank"""")),
                ).andExpect(content().string(not(containsString("reports/tests/test/packages/index.html"))))
        }

        test("artifact index of a pruned build explains the missing artifacts") {
            every { repository.history() } returns listOf(successResult)
            every { artifactStore.artifactDir("main-abc123-key") } returns null

            mockMvc
                .perform(get("/builds/main-abc123-key"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("No log files are stored for this build")))
        }

        test("permanent artifact index renders the latest green build with permanent file links") {
            val artifactDir = Files.createDirectories(tempDir.resolve("permanent-main-key"))
            Files.writeString(artifactDir.resolve("build.stdout.log"), "out")
            Files.createDirectories(artifactDir.resolve("reports/tests/test"))
            Files.writeString(artifactDir.resolve("reports/tests/test/index.html"), "<html></html>")
            every { branchPermalinks.latestGreenBuild("main") } returns successResult
            every { artifactStore.artifactDir("main-abc123-key") } returns artifactDir

            mockMvc
                .perform(get("/branches/main"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("latest green build of branch")))
                .andExpect(content().string(containsString("""/branches/main/build.stdout.log" target="_blank"""")))
                .andExpect(content().string(containsString("""/branches/main/reports/tests/test/index.html"""")))
                .andExpect(content().string(containsString("/builds/main-abc123-key")))
                .andExpect(content().string(not(containsString("/artifacts/main-abc123-key"))))
        }

        test("permanent artifact index of a branch without a green build answers 404") {
            every { branchPermalinks.latestGreenBuild("main") } throws
                ResponseStatusException(HttpStatus.NOT_FOUND, "branch 'main' has no successful build")

            mockMvc
                .perform(get("/branches/main"))
                .andExpect(status().isNotFound)
        }

        test("artifact index of an unknown key answers 404") {
            every { repository.history() } returns emptyList()
            every { artifactStore.artifactDir("no-such-key") } returns null

            mockMvc
                .perform(get("/builds/no-such-key"))
                .andExpect(status().isNotFound)
        }

        test("system view renders metric rows, totals, and the polling hook") {
            every { metricsCollector.snapshot() } returns
                emptySystemMetrics.copy(
                    timestamp = Instant.parse("2026-07-07T10:00:00Z"),
                    sampleCount = 5,
                    ramTotalGib = 32.0,
                    cpuUsed = MetricAggregate(current = 1.0, min = 0.5, max = 2.0, avg = 1.25),
                )

            mockMvc
                .perform(get("/system"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("""data-api="/api/system"""")))
                .andExpect(content().string(containsString("""data-metric="cpuUsed"""")))
                .andExpect(content().string(containsString("CPU used (cores)")))
                .andExpect(content().string(containsString("1.25")))
                .andExpect(content().string(containsString("8 cores")))
                .andExpect(content().string(containsString("32.00 GiB")))
        }

        test("system view renders n/a for unavailable metrics") {
            every { metricsCollector.snapshot() } returns emptySystemMetrics

            mockMvc
                .perform(get("/system"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("Repo size (GiB)")))
                .andExpect(content().string(containsString("n/a")))
        }

        test("branch names with HTML metacharacters render escaped") {
            val nasty = "feat/<script>alert('x')</script>"
            every { repository.latestPerBranch() } returns listOf(successResult.copy(branch = nasty))

            mockMvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(containsString("&lt;script&gt;")))
        }
    }
}
