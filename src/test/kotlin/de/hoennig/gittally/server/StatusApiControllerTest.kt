package de.hoennig.werkator.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.gitea.GiteaClient
import de.hoennig.werkator.gitea.GiteaStatusResult
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(StatusApiController::class, properties = ["spring.main.web-application-type=servlet"])
class StatusApiControllerTest : FunSpec() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var repository: BuildResultRepository

    @MockkBean
    lateinit var giteaClient: GiteaClient

    private val commit = "0123456789abcdef0123456789abcdef01234567"

    private val localResult =
        BuildResult(
            branch = "main",
            commit = commit,
            status = BuildStatus.FAILED,
            startedAt = Instant.parse("2026-07-07T10:00:00Z"),
            duration = null,
            artifactKey = "main-abc123-key",
        )

    init {
        beforeEach {
            clearMocks(repository, giteaClient)
            every { repository.history() } returns emptyList()
        }

        test("prefers the Gitea status over the local status") {
            every { repository.history() } returns listOf(localResult)
            every { giteaClient.readStatus(commit, any()) } returns GiteaStatusResult.Found(BuildStatus.SUCCESS)

            mockMvc
                .perform(get("/api/status/$commit"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.giteaStatus").value("success"))
                .andExpect(jsonPath("$.localStatus").value("failed"))
                .andExpect(jsonPath("$.giteaError").doesNotExist())
        }

        test("falls back to the local status when Gitea is disabled") {
            every { repository.history() } returns listOf(localResult)
            every { giteaClient.readStatus(commit, any()) } returns GiteaStatusResult.Disabled

            mockMvc
                .perform(get("/api/status/$commit"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.giteaStatus").doesNotExist())
        }

        test("resolves an abbreviated commit hash against the local history") {
            every { repository.history() } returns listOf(localResult)
            every { giteaClient.readStatus(any(), any()) } returns GiteaStatusResult.None

            mockMvc
                .perform(get("/api/status/${commit.take(8)}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("failed"))
        }

        test("Gitea failure without a local build answers 200 with an explicit unknown status") {
            every { giteaClient.readStatus(commit, any()) } returns GiteaStatusResult.Error("Gitea status request failed")

            mockMvc
                .perform(get("/api/status/$commit"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("unknown"))
                .andExpect(jsonPath("$.giteaError").value("Gitea status request failed"))
        }

        test("rejects malformed commit hashes") {
            mockMvc
                .perform(get("/api/status/not-a-commit"))
                .andExpect(status().isBadRequest)
            mockMvc
                .perform(get("/api/status/abc123"))
                .andExpect(status().isBadRequest)
        }
    }
}
