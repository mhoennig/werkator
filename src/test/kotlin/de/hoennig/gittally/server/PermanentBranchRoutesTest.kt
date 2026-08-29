package de.hoennig.gittally.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildExecutor
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.config.GitTallyConfig
import de.hoennig.gittally.git.GitService
import de.hoennig.gittally.metrics.SystemMetricsCollector
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The three `/branches…` routes live in two controllers; this slice registers both
 * and proves the mappings coexist: the exact list page, the permanent index page,
 * and the catch-all permanent file route.
 */
@WebMvcTest(
    controllers = [UiController::class, ArtifactFileController::class],
    properties = ["spring.main.web-application-type=servlet"],
)
class PermanentBranchRoutesTest : FunSpec() {
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

    private val artifactDir: Path = Files.createTempDirectory("gittally-permanent-routes-test")

    private val greenBuild =
        BuildResult(
            branch = "main",
            commit = "0123456789abcdef",
            status = BuildStatus.SUCCESS,
            startedAt = Instant.parse("2026-07-07T10:00:00Z"),
            duration = Duration.ofSeconds(83),
            artifactKey = "main-key",
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
            )
            every { configLoader.load(any()) } returns GitTallyConfig()
            every { configLoader.loadWithBranchLayer(any(), anyNullable()) } returns GitTallyConfig()
            every { gitService.showFileAtCommit(any(), any(), any()) } returns null
            every { controlTokens.token() } returns "test-token"
            every { branchListing.branches(any()) } returns emptyList()
            every { branchPermalinks.latestGreenBuild("main") } returns greenBuild
            every { artifactStore.artifactDir("main-key") } returns artifactDir
        }

        test("/branches still renders the branch list page") {
            mockMvc
                .perform(get("/branches"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("""data-api="/api/branches"""")))
        }

        test("/branches/<key> renders the permanent artifact index page") {
            mockMvc
                .perform(get("/branches/main"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("latest green build of branch")))
        }

        test("/branches/<key>/<path> serves the artifact file") {
            Files.writeString(artifactDir.resolve("build.log"), "line one")

            mockMvc
                .perform(get("/branches/main/build.log"))
                .andExpect(status().isOk)
                .andExpect(content().string("line one"))
        }
    }
}
