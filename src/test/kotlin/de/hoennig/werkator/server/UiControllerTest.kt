package de.hoennig.werkator.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.build.RunningBuild
import de.hoennig.werkator.config.BranchConfig
import de.hoennig.werkator.config.BuildDefinition
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.config.GiteaConfig
import de.hoennig.werkator.config.ServerConfig
import de.hoennig.werkator.config.WerkatorConfig
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.metrics.MetricAggregate
import de.hoennig.werkator.metrics.SystemMetrics
import de.hoennig.werkator.metrics.SystemMetricsCollector
import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoLinks
import de.hoennig.werkator.repo.RepoRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

@WebMvcTest(UiController::class, properties = ["spring.main.web-application-type=servlet"])
@Import(RepoLinks::class)
class UiControllerTest : FunSpec() {
    private val tempDir: Path = Files.createTempDirectory("werkator-ui-test")

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
    lateinit var gitService: GitService

    @MockkBean
    lateinit var metricsCollector: SystemMetricsCollector

    @MockkBean
    lateinit var branchListing: BranchListing

    @MockkBean
    lateinit var branchPermalinks: BranchPermalinks

    @MockkBean
    lateinit var repo: RepoContext

    @MockkBean
    lateinit var registry: RepoRegistry

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
                gitService,
                metricsCollector,
                branchListing,
                branchPermalinks,
                repo,
                registry,
            )
            every { repo.name } returns "test"
            every { repo.workingDir } returns Paths.get(".")
            every { repo.results } returns repository
            every { repo.artifactStore } returns artifactStore
            every { registry.all() } returns listOf(repo)
            every { registry.current() } returns repo
            every { registry.byName(any()) } returns null
            every { registry.byName("test") } returns repo
            every { configLoader.load(any()) } returns
                WerkatorConfig(
                    server = ServerConfig(impressumUrl = "https://example.org/imprint"),
                    gitea = GiteaConfig(baseUrl = "https://git.example.org", owner = "acme", repo = "widget"),
                )
            every { configLoader.loadWithBranchLayer(any(), anyNullable()) } returns WerkatorConfig()
            every { gitService.showFileAtCommit(any(), any(), any()) } returns null
            every { controlTokens.token() } returns "test-token"
            every { repository.latestGreenFor(any()) } returns null
        }

        test("latest view renders the empty state, and the nav no longer offers the current view") {
            every { repository.latestPerName() } returns emptyList()

            mockMvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("No builds recorded yet.")))
                .andExpect(content().string(containsString("""data-api="/api/builds/latest"""")))
                .andExpect(content().string(containsString("""id="reload-button"""")))
                .andExpect(content().string(not(containsString("""href="/current""""))))
        }

        test("with one served repository the pages keep their existing URLs and show no switcher") {
            every { repository.latestPerName() } returns listOf(successResult)

            mockMvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("""href="/branches"""")))
                // Thymeleaf drops an attribute whose value is empty, and werkator.js falls back to ""
                .andExpect(content().string(containsString("""<meta name="werkator-repo-base">""")))
                .andExpect(content().string(not(containsString("""class="repo-switch""""))))
        }

        test("with several served repositories every link names its repository and the switcher appears") {
            val other = mockk<RepoContext>()
            every { other.name } returns "other"
            every { registry.all() } returns listOf(repo, other)
            every { repository.latestPerName() } returns listOf(successResult)

            mockMvc
                .perform(get("/repos/test"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("""href="/repos/test/branches"""")))
                .andExpect(content().string(containsString("""data-api="/api/repos/test/builds/latest"""")))
                .andExpect(content().string(containsString("""<meta name="werkator-repo-base" content="/repos/test">""")))
                .andExpect(content().string(containsString("""class="repo-switch"""")))
                .andExpect(content().string(containsString("""href="/repos/other"""")))
        }

        test("a page of a repository this instance does not serve answers 404") {
            mockMvc
                .perform(get("/repos/no-such-repo"))
                .andExpect(status().isNotFound)
        }

        test("latest view renders rows with badge, Gitea links, artifact link, actions, and token") {
            every { repository.latestPerName() } returns listOf(successResult)

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
                // the control token must never reach the browser: reading a page is unauthenticated
                .andExpect(content().string(not(containsString("werkator-control-token"))))
                .andExpect(content().string(not(containsString("test-token"))))
                .andExpect(content().string(containsString("https://example.org/imprint")))
                .andExpect(content().string(containsString("1:23")))
        }

        test("branches view renders built and never-built branches with restart actions") {
            every { branchListing.branches(any()) } returns
                listOf(
                    BranchDto.from("main", "ignored-head", successResult, isLatestGreen = true),
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

        test("the branches view restarts at the branch's origin head, the latest view repeats the run") {
            every { branchListing.branches(any()) } returns
                listOf(BranchDto.from("main", "ignored-head", successResult, isLatestGreen = true))
            every { repository.latestPerName() } returns listOf(successResult)

            // a row on /branches stands for a branch, so its button builds the branch as it is now
            mockMvc
                .perform(get("/branches"))
                .andExpect(content().string(containsString("""data-restart-at-origin-head="true"""")))
                .andExpect(content().string(containsString("Build current head")))

            // a row on / stands for a recorded run, and repeating it means that commit
            mockMvc
                .perform(get("/"))
                .andExpect(content().string(containsString("""data-restart-at-origin-head="false"""")))
                .andExpect(content().string(containsString("Restart build")))
        }

        test("the permanent link shows on the branch's latest green build only, the live link while it runs") {
            val running = successResult.copy(status = BuildStatus.RUNNING, duration = null, artifactKey = "running-key")
            every { repository.history() } returns listOf(running, successResult)
            every { repository.latestGreenFor("main") } returns successResult

            val page =
                mockMvc
                    .perform(get("/history"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            Regex("""href="/branches/main"""").findAll(page).count() shouldBe 1
            Regex("""href="/current"""").findAll(page).count() shouldBe 1
            page shouldContain "Watch this build live"
        }

        test("history view renders mixed history without restart actions") {
            every { repository.history() } returns
                listOf(
                    successResult.copy(
                        branch = "main",
                        name = "main",
                        status = BuildStatus.RUNNING,
                        duration = null,
                        artifactKey = "run-key",
                    ),
                    successResult,
                    successResult.copy(branch = "feature/x", name = "feature/x", status = BuildStatus.FAILED, artifactKey = "failed-key"),
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
                    repo = repo,
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

        test("artifact index lists plain files outside reports/ and keeps logs and report files out of that list") {
            val artifactDir = Files.createDirectories(tempDir.resolve("files-view-key"))
            Files.writeString(artifactDir.resolve("build.stdout.log"), "out")
            Files.createDirectories(artifactDir.resolve("werkdock/dist"))
            Files.writeString(artifactDir.resolve("werkdock/dist/werkdock"), "elf")
            Files.createDirectories(artifactDir.resolve("reports/tests"))
            Files.writeString(artifactDir.resolve("reports/tests/index.html"), "<html></html>")
            every { repository.history() } returns listOf(successResult)
            every { artifactStore.artifactDir("files-view-key") } returns artifactDir

            val page =
                mockMvc
                    .perform(get("/builds/files-view-key"))
                    .andExpect(status().isOk)
                    .andExpect(
                        content().string(containsString("""/artifacts/files-view-key/werkdock/dist/werkdock" target="_blank"""")),
                    ).andReturn()
                    .response.contentAsString
            // stored at its own path, not below reports/; the log stays in the
            // logs section and is not repeated in the files list
            page shouldNotContain "reports/werkdock"
            (page.split("/artifacts/files-view-key/build.stdout.log").size - 1) shouldBe 1
        }

        test("the artifact page shows the command of the build's own definition, not the plain branch command") {
            val pitestResult =
                successResult.copy(
                    build = "pitest",
                    name = "main@pitest",
                    artifactKey = "main-pitest-key",
                )
            every { repository.history() } returns listOf(pitestResult)
            every { artifactStore.artifactDir("main-pitest-key") } returns null
            every { configLoader.loadWithBranchLayer(any(), anyNullable()) } returns
                WerkatorConfig(
                    branches = mapOf("default" to BranchConfig(buildCommand = "./gradlew quick-check")),
                    buildDefinitions = mapOf("pitest" to BuildDefinition(buildCommand = "./gradlew pitestFull")),
                )

            mockMvc
                .perform(get("/builds/main-pitest-key"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("./gradlew pitestFull")))
                .andExpect(content().string(not(containsString("./gradlew quick-check"))))
        }

        test("artifact index links a single index-less report page as a directory, keeping the URL stable") {
            val artifactDir = Files.createDirectories(tempDir.resolve("main-abc123-key"))
            Files.createDirectories(artifactDir.resolve("reports/profile"))
            Files.writeString(artifactDir.resolve("reports/profile/profile-2026-08-10-18-36-12.html"), "<html></html>")
            Files.createDirectories(artifactDir.resolve("reports/tests/test"))
            Files.writeString(artifactDir.resolve("reports/tests/test/index.html"), "<html></html>")
            Files.createDirectories(artifactDir.resolve("reports/tests/test/classes"))
            Files.writeString(artifactDir.resolve("reports/tests/test/classes/SomeTest.html"), "<html></html>")
            every { repository.history() } returns listOf(successResult)
            every { artifactStore.artifactDir("main-abc123-key") } returns artifactDir

            val page =
                mockMvc
                    .perform(get("/builds/main-abc123-key"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            page shouldContain "reports/profile/"
            page shouldNotContain "profile-2026-08-10-18-36-12.html"
            page shouldContain "reports/tests/test/index.html"
            page shouldNotContain "SomeTest.html"
        }

        test("artifact index links the pages of an index-less report directory holding several") {
            val artifactDir = Files.createDirectories(tempDir.resolve("main-abc123-key"))
            Files.createDirectories(artifactDir.resolve("reports/pmd"))
            Files.writeString(artifactDir.resolve("reports/pmd/main.html"), "<html></html>")
            Files.writeString(artifactDir.resolve("reports/pmd/test.html"), "<html></html>")
            every { repository.history() } returns listOf(successResult)
            every { artifactStore.artifactDir("main-abc123-key") } returns artifactDir

            val page =
                mockMvc
                    .perform(get("/builds/main-abc123-key"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            page shouldContain "reports/pmd/main.html"
            page shouldContain "reports/pmd/test.html"
        }

        test("report links carry a failed-badge from the report's failures counter") {
            val artifactDir = Files.createDirectories(tempDir.resolve("main-abc123-key"))
            Files.createDirectories(artifactDir.resolve("reports/tests/test"))
            Files.writeString(
                artifactDir.resolve("reports/tests/test/index.html"),
                """<div class="infoBox" id="failures">
                <div class="counter">2</div>
                <p>failures</p></div>""",
            )
            Files.createDirectories(artifactDir.resolve("reports/tests/unitTest"))
            Files.writeString(
                artifactDir.resolve("reports/tests/unitTest/index.html"),
                """<div class="infoBox" id="failures">
                <div class="counter">0</div>
                <p>failures</p></div>""",
            )
            Files.createDirectories(artifactDir.resolve("reports/jacoco"))
            Files.writeString(artifactDir.resolve("reports/jacoco/index.html"), "<html>no counter</html>")
            every { repository.history() } returns listOf(successResult)
            every { artifactStore.artifactDir("main-abc123-key") } returns artifactDir

            val page =
                mockMvc
                    .perform(get("/builds/main-abc123-key"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            page shouldContain "2 failed"
            page shouldNotContain "0 failed"
            // exactly one badge: the report with failures, none for the clean or counter-less reports
            Regex(""" failed</span>""").findAll(page).count() shouldBe 1
        }

        test("of a failed build, only the logs carrying a failure line get a failed-badge") {
            val artifactDir = Files.createDirectories(tempDir.resolve("failed-key"))
            Files.writeString(artifactDir.resolve("build.log"), "compiling\nBUILD FAILED in 20s\n")
            // a failed test, far away from any BUILD FAILED line
            Files.writeString(artifactDir.resolve("build.stdout.log"), "SomeTest > works() FAILED\ncompiling\n")
            Files.writeString(artifactDir.resolve("build.stderr.log"), "warning: this test has failed before\n")
            every { repository.history() } returns
                listOf(successResult.copy(status = BuildStatus.FAILED, artifactKey = "failed-key"))
            every { artifactStore.artifactDir("failed-key") } returns artifactDir

            val page =
                mockMvc
                    .perform(get("/builds/failed-key"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            // two badges for the logs, plus the one of the build status itself
            Regex(""">failed</span>""").findAll(page).count() shouldBe 3
            // lower-case prose about failing is not a failure line
            page.substringAfter("build.stderr.log</a>").substringBefore("</li>") shouldNotContain "status-failed"
        }

        test("logs of a successful build are not scanned for failure lines") {
            val artifactDir = Files.createDirectories(tempDir.resolve("green-key"))
            Files.writeString(artifactDir.resolve("build.log"), "BUILD FAILED in a nested build\nBUILD SUCCESSFUL\n")
            every { repository.history() } returns listOf(successResult.copy(artifactKey = "green-key"))
            every { artifactStore.artifactDir("green-key") } returns artifactDir

            val page =
                mockMvc
                    .perform(get("/builds/green-key"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString

            page shouldContain "build.log"
            page shouldNotContain "status-failed"
        }

        test("legacy page names redirect permanently to the new routes") {
            mockMvc
                .perform(get("/index.html"))
                .andExpect(status().isMovedPermanently)
                .andExpect(header().string("Location", "/"))
            mockMvc
                .perform(get("/branches.html"))
                .andExpect(status().isMovedPermanently)
                .andExpect(header().string("Location", "/branches"))
            mockMvc
                .perform(get("/history.html"))
                .andExpect(status().isMovedPermanently)
                .andExpect(header().string("Location", "/history"))
        }

        test("release notes page renders the version history and the footer links to it") {
            mockMvc
                .perform(get("/releases"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("Release Notes")))
                .andExpect(content().string(containsString("v0.9.0")))
                .andExpect(content().string(containsString("Kotlin/Spring Boot")))
                // the footer version (rendered on every page) is the link to this page
                .andExpect(content().string(containsString("""href="/releases"""")))
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
            every { branchPermalinks.latestGreenBuild(any(), "main") } returns successResult
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
            every { branchPermalinks.latestGreenBuild(any(), "main") } throws
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
            every { repository.latestPerName() } returns listOf(successResult.copy(branch = nasty, name = nasty))

            mockMvc
                .perform(get("/"))
                .andExpect(status().isOk)
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(containsString("&lt;script&gt;")))
        }
    }
}
