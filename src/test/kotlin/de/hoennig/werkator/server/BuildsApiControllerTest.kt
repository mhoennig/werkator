package de.hoennig.werkator.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.build.RunningBuild
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoLinks
import de.hoennig.werkator.repo.RepoRegistry
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
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
@Import(RepoLinks::class)
class BuildsApiControllerTest : FunSpec() {
    private val tempDir: Path = Files.createTempDirectory("werkator-server-test")

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
    lateinit var gitService: GitService

    @MockkBean
    lateinit var branchListing: BranchListing

    @MockkBean
    lateinit var repo: RepoContext

    @MockkBean
    lateinit var registry: RepoRegistry

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
            repo = repo,
            branch = "main",
            commit = successResult.commit,
            artifactKey = "main-abc123-running",
            startedAt = startedAt,
            stagingDir = liveLogFile.parent,
            liveLogFile = liveLogFile,
        )

    init {
        beforeEach {
            clearMocks(repository, buildExecutor, artifactStore, controlTokens, gitService, branchListing, repo, registry)
            every { repo.name } returns "test"
            every { repo.workingDir } returns tempDir
            every { repo.results } returns repository
            every { repo.artifactStore } returns artifactStore
            // the unscoped routes mean the served repository; `/api/repos/test/…` names it
            every { registry.all() } returns listOf(repo)
            every { registry.current() } returns repo
            every { registry.byName(any()) } returns null
            every { registry.byName("test") } returns repo
            every { controlTokens.matches(any()) } answers { firstArg<String?>() == "secret" }
            every { repository.latestGreenFor(any()) } returns null
        }

        test("latest answers one entry per branch with lowercase status and duration in seconds") {
            every { repository.latestPerName() } returns listOf(successResult)

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

        test("history carries the permanent URL on the branch's latest green build only") {
            val older = successResult.copy(artifactKey = "older-key")
            every { repository.history() } returns listOf(successResult, older)
            every { repository.latestGreenFor("main") } returns successResult

            mockMvc
                .perform(get("/api/builds/history"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].latestGreenUrl").value("/branches/main"))
                .andExpect(jsonPath("$[1].latestGreenUrl").doesNotExist())
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

        test("current answers only the served repository's builds") {
            val liveLogFile = Files.writeString(tempDir.resolve("mine.log"), "12345")
            val mine = runningBuild(liveLogFile)
            val foreign =
                runningBuild(liveLogFile).copy(
                    repo = mockk<RepoContext>(),
                    artifactKey = "other-repo-running",
                )
            every { buildExecutor.currentBuilds() } returns listOf(mine, foreign)
            every { repository.history() } returns
                listOf(successResult.copy(status = BuildStatus.RUNNING, artifactKey = mine.artifactKey))

            mockMvc
                .perform(get("/api/builds/current"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].artifactKey").value(mine.artifactKey))
        }

        test("current log of a build in another repository answers 404") {
            val liveLogFile = Files.writeString(tempDir.resolve("foreign.log"), "hello world")
            val foreign = runningBuild(liveLogFile).copy(repo = mockk<RepoContext>())
            every { buildExecutor.currentBuilds() } returns listOf(foreign)

            mockMvc
                .perform(get("/api/builds/current/${foreign.artifactKey}/log"))
                .andExpect(status().isNotFound)
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
            every { repository.latestFor("feature/topic") } returns successResult.copy(branch = "feature/topic", name = "feature/topic")
            every { buildExecutor.startBuild(repo, "feature/topic", successResult.commit) } returns
                runningBuild(liveLogFile).copy(branch = "feature/topic", name = "feature/topic")

            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "feature/topic")
                        .header(BuildsApiController.TOKEN_HEADER, "secret"),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.artifactKey").value("main-abc123-running"))

            verify { buildExecutor.startBuild(repo, "feature/topic", successResult.commit) }
        }

        test("restart of a named build re-runs its build definition on its real branch") {
            val liveLogFile = tempDir.resolve("named-restart.log")
            every { repository.latestFor("main@pitest") } returns
                successResult.copy(build = "pitest", name = "main@pitest")
            every { buildExecutor.startBuild(repo, "main", successResult.commit, build = "pitest") } returns
                runningBuild(liveLogFile).copy(build = "pitest", name = "main@pitest")

            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "main@pitest")
                        .header(BuildsApiController.TOKEN_HEADER, "secret"),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.name").value("main@pitest"))

            // the re-run resolves its settings from the current config by the build name
            verify { buildExecutor.startBuild(repo, "main", successResult.commit, build = "pitest") }
        }

        test("restart with atOriginHead builds the branch as it is now, not the recorded commit") {
            val liveLogFile = tempDir.resolve("head-restart.log")
            every { repository.latestFor("main") } returns successResult
            every { gitService.originHeadCommit("main", any()) } returns "newhead1"
            every { buildExecutor.startBuild(repo, "main", "newhead1") } returns runningBuild(liveLogFile)

            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "main")
                        .param("atOriginHead", "true")
                        .header(BuildsApiController.TOKEN_HEADER, "secret"),
                ).andExpect(status().isAccepted)

            // the recorded commit is deliberately not used: a branches row stands for a branch
            verify { buildExecutor.startBuild(repo, "main", "newhead1") }
            verify(exactly = 0) { buildExecutor.startBuild(repo, "main", successResult.commit) }
        }

        test("restart with atOriginHead keeps the recorded build definition and its real branch") {
            val liveLogFile = tempDir.resolve("head-named.log")
            every { repository.latestFor("main@pitest") } returns successResult.copy(build = "pitest", name = "main@pitest")
            every { gitService.originHeadCommit("main", any()) } returns "newhead2"
            every { buildExecutor.startBuild(repo, "main", "newhead2", build = "pitest") } returns
                runningBuild(liveLogFile).copy(build = "pitest", name = "main@pitest")

            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "main@pitest")
                        .param("atOriginHead", "true")
                        .header(BuildsApiController.TOKEN_HEADER, "secret"),
                ).andExpect(status().isAccepted)

            verify { buildExecutor.startBuild(repo, "main", "newhead2", build = "pitest") }
        }

        test("restart with atOriginHead of a branch gone from origin is refused by name") {
            every { repository.latestFor("gone") } returns successResult.copy(branch = "gone", name = "gone")
            every { gitService.originHeadCommit("gone", any()) } returns null

            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "gone")
                        .param("atOriginHead", "true")
                        .header(BuildsApiController.TOKEN_HEADER, "secret"),
                ).andExpect(status().isNotFound)

            verify(exactly = 0) { buildExecutor.startBuild(any(), any(), any(), any()) }
        }

        test("restart of a never-built branch enqueues its origin head commit") {
            val liveLogFile = tempDir.resolve("first-build.log")
            every { repository.latestFor("fresh") } returns null
            every { gitService.originHeadCommit("fresh", any()) } returns successResult.commit
            every { buildExecutor.startBuild(repo, "fresh", successResult.commit) } returns
                runningBuild(liveLogFile).copy(branch = "fresh", name = "fresh")

            mockMvc
                .perform(post("/api/builds/restart").param("branch", "fresh").header(BuildsApiController.TOKEN_HEADER, "secret"))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("pending"))

            verify { buildExecutor.startBuild(repo, "fresh", successResult.commit) }
        }

        test("restart of a branch without recorded builds and without origin counterpart answers 404") {
            every { repository.latestFor("gone") } returns null
            every { gitService.originHeadCommit("gone", any()) } returns null

            mockMvc
                .perform(post("/api/builds/restart").param("branch", "gone").header(BuildsApiController.TOKEN_HEADER, "secret"))
                .andExpect(status().isNotFound)
        }

        test("branches answers the branch listing with unknown placeholders for never-built branches") {
            every { branchListing.branches(any()) } returns
                listOf(
                    BranchDto.from("main", "ignored-head", successResult),
                    BranchDto.from("feature/x", "fedcba9876543210fedcba9876543210fedcba98", null),
                )

            mockMvc
                .perform(get("/api/branches"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].branch").value("main"))
                .andExpect(jsonPath("$[0].status").value("success"))
                .andExpect(jsonPath("$[0].commit").value(successResult.commit))
                .andExpect(jsonPath("$[1].branch").value("feature/x"))
                .andExpect(jsonPath("$[1].status").value("unknown"))
                .andExpect(jsonPath("$[1].commit").value("fedcba9876543210fedcba9876543210fedcba98"))
                .andExpect(jsonPath("$[1].startedAt").doesNotExist())
                .andExpect(jsonPath("$[1].artifactKey").value(""))
        }

        test("restart with a wrong token answers 403 and does not build") {
            mockMvc
                .perform(
                    post("/api/builds/restart")
                        .param("branch", "main")
                        .header(BuildsApiController.TOKEN_HEADER, "wrong"),
                ).andExpect(status().isForbidden)

            verify(exactly = 0) { buildExecutor.startBuild(any(), any(), any(), any()) }
        }

        test("cancel answers 202 for a cancellable build and 404 otherwise") {
            every { repository.history() } returns
                listOf(successResult.copy(artifactKey = "known-key"), successResult.copy(artifactKey = "unknown-key"))
            every { buildExecutor.cancel("known-key") } returns true
            every { buildExecutor.cancel("unknown-key") } returns false

            mockMvc
                .perform(post("/api/builds/known-key/cancel").header(BuildsApiController.TOKEN_HEADER, "secret"))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.cancelled").value("known-key"))
            mockMvc
                .perform(post("/api/builds/unknown-key/cancel").header(BuildsApiController.TOKEN_HEADER, "secret"))
                .andExpect(status().isNotFound)
        }

        test("cancel does not reach a build of another repository") {
            // the key exists in the executor, but not in this repository's results
            every { repository.history() } returns listOf(successResult)
            every { buildExecutor.cancel(any()) } returns true

            mockMvc
                .perform(
                    post("/api/repos/test/builds/other-repo-key/cancel")
                        .header(BuildsApiController.TOKEN_HEADER, "secret"),
                ).andExpect(status().isNotFound)
            verify(exactly = 0) { buildExecutor.cancel(any()) }
        }

        test("the repository-scoped routes answer for the named repository and 404 for an unknown name") {
            every { repository.latestPerName() } returns listOf(successResult)

            mockMvc
                .perform(get("/api/repos/test/builds/latest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].artifactKey").value("main-abc123-key"))
            mockMvc
                .perform(get("/api/repos/no-such-repo/builds/latest"))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.error").value("no repository named 'no-such-repo'"))
        }

        test("a token in the query string is not accepted — the header is the only way") {
            mockMvc
                .perform(post("/api/builds/restart").param("branch", "main").param("token", "secret"))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(post("/api/builds/some-key/cancel").param("token", "secret"))
                .andExpect(status().isForbidden)
            mockMvc
                .perform(delete("/api/builds/some-key").param("token", "secret"))
                .andExpect(status().isForbidden)

            verify(exactly = 0) { buildExecutor.startBuild(any(), any(), any(), any()) }
            verify(exactly = 0) { buildExecutor.cancel(any()) }
            verify(exactly = 0) { repository.delete(any()) }
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
                .perform(delete("/api/builds/unknown-key").header(BuildsApiController.TOKEN_HEADER, "secret"))
                .andExpect(status().isNotFound)

            verify(exactly = 0) { artifactStore.prune(any()) }
        }
    }
}
