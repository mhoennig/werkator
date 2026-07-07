package de.hoennig.gittally.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildExecutor
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.build.RunningBuild
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

@WebMvcTest(BuildsApiController::class, properties = ["spring.main.web-application-type=servlet"])
class BuildsApiControllerTest : FunSpec() {
    private val tempDir: Path = Files.createTempDirectory("gittally-server-test")

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

    private val startedAt = Instant.parse("2026-07-07T10:00:00Z")

    private val successResult =
        BuildResult(
            branch = "main",
            commit = "0123456789abcdef0123456789abcdef01234567",
            status = BuildStatus.SUCCESS,
            startedAt = startedAt,
            duration = Duration.ofSeconds(83),
            artifactKey = "main-abc123-key",
        )

    private fun runningBuild(liveLogFile: Path) =
        RunningBuild(
            branch = "main",
            commit = successResult.commit,
            artifactKey = "main-abc123-running",
            startedAt = startedAt,
            stagingDir = liveLogFile.parent,
            liveLogFile = liveLogFile,
        )

    init {
        beforeEach {
            clearMocks(repository, buildExecutor, artifactStore, controlTokens)
            every { controlTokens.matches(any()) } answers { firstArg<String?>() == "secret" }
        }

        test("latest answers one entry per branch with lowercase status and duration in seconds") {
            every { repository.latestPerBranch() } returns listOf(successResult)

            mockMvc
                .perform(get("/api/builds/latest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].branch").value("main"))
                .andExpect(jsonPath("$[0].status").value("success"))
                .andExpect(jsonPath("$[0].durationSeconds").value(83))
                .andExpect(jsonPath("$[0].artifactKey").value("main-abc123-key"))
        }

        test("history answers all builds") {
            every { repository.history() } returns listOf(successResult, successResult.copy(status = BuildStatus.FAILED))

            mockMvc
                .perform(get("/api/builds/history"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].status").value("failed"))
        }

        test("current answers the running builds with live status and log size") {
            val liveLogFile = Files.writeString(tempDir.resolve("build.log"), "12345")
            val build = runningBuild(liveLogFile)
            every { buildExecutor.currentBuilds() } returns listOf(build)
            every { repository.history() } returns
                listOf(successResult.copy(status = BuildStatus.RUNNING, artifactKey = build.artifactKey))

            mockMvc
                .perform(get("/api/builds/current"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].artifactKey").value(build.artifactKey))
                .andExpect(jsonPath("$[0].status").value("running"))
                .andExpect(jsonPath("$[0].logSize").value(5))
        }

        test("current log answers the tail from the requested offset") {
            val liveLogFile = Files.writeString(tempDir.resolve("tail.log"), "hello world")
            val build = runningBuild(liveLogFile)
            every { buildExecutor.currentBuilds() } returns listOf(build)

            mockMvc
                .perform(get("/api/builds/current/${build.artifactKey}/log").param("offset", "6"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content").value("world"))
                .andExpect(jsonPath("$.nextOffset").value(11))
        }

        test("current log of an unknown artifact key answers 404") {
            every { buildExecutor.currentBuilds() } returns emptyList()

            mockMvc
                .perform(get("/api/builds/current/no-such-key/log"))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error").exists())
        }

        test("restart enqueues the branch's last recorded commit, also for branch names with slashes") {
            val liveLogFile = tempDir.resolve("restart.log")
            every { repository.latestFor("feature/topic") } returns successResult.copy(branch = "feature/topic")
            every { buildExecutor.startBuild("feature/topic", successResult.commit) } returns
                runningBuild(liveLogFile).copy(branch = "feature/topic")

            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "feature/topic")
                        .header(BuildsApiController.TOKEN_HEADER, "secret"),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.artifactKey").value("main-abc123-running"))

            verify { buildExecutor.startBuild("feature/topic", successResult.commit) }
        }

        test("restart of a branch without recorded builds answers 404") {
            every { repository.latestFor("gone") } returns null

            mockMvc
                .perform(post("/api/builds/restart").param("branch", "gone").param("token", "secret"))
                .andExpect(status().isNotFound)
        }

        test("restart with a wrong token answers 403 and does not build") {
            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "main")
                        .header(BuildsApiController.TOKEN_HEADER, "wrong"),
                ).andExpect(status().isForbidden)

            verify(exactly = 0) { buildExecutor.startBuild(any(), any(), any()) }
        }

        test("cancel answers 202 for a cancellable build and 404 otherwise") {
            every { buildExecutor.cancel("known-key") } returns true
            every { buildExecutor.cancel("unknown-key") } returns false

            mockMvc
                .perform(post("/api/builds/known-key/cancel").param("token", "secret"))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.cancelled").value("known-key"))
            mockMvc
                .perform(post("/api/builds/unknown-key/cancel").param("token", "secret"))
                .andExpect(status().isNotFound)
        }

        test("cancel without token answers 403") {
            mockMvc
                .perform(post("/api/builds/some-key/cancel"))
                .andExpect(status().isForbidden)

            verify(exactly = 0) { buildExecutor.cancel(any()) }
        }

        test("delete removes the result and prunes its artifacts") {
            every { repository.delete("old-key") } returns true
            every { repository.history() } returns listOf(successResult)
            every { artifactStore.prune(any()) } returns listOf("old-key")

            mockMvc
                .perform(delete("/api/builds/old-key").header(BuildsApiController.TOKEN_HEADER, "secret"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.deleted").value("old-key"))

            verify { artifactStore.prune(listOf(successResult)) }
        }

        test("delete of an unknown artifact key answers 404 without pruning") {
            every { repository.delete("unknown-key") } returns false

            mockMvc
                .perform(delete("/api/builds/unknown-key").param("token", "secret"))
                .andExpect(status().isNotFound)

            verify(exactly = 0) { artifactStore.prune(any()) }
        }
    }
}
