package de.hoennig.werkator.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildStatus
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

@WebMvcTest(ArtifactFileController::class, properties = ["spring.main.web-application-type=servlet"])
class ArtifactFileControllerTest : FunSpec() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var artifactStore: ArtifactStore

    @MockkBean
    lateinit var branchPermalinks: BranchPermalinks

    private val artifactDir: Path = Files.createTempDirectory("werkator-artifact-serve-test")

    private val greenBuild =
        BuildResult(
            branch = "main",
            commit = "0123456789abcdef",
            status = BuildStatus.SUCCESS,
            startedAt = Instant.parse("2026-07-07T10:00:00Z"),
            duration = Duration.ofSeconds(83),
            artifactKey = "known-key",
        )

    init {
        beforeEach {
            clearMocks(artifactStore, branchPermalinks)
            every { artifactStore.artifactDir(any()) } returns null
            every { artifactStore.artifactDir("known-key") } returns artifactDir
            every { branchPermalinks.latestGreenBuild(any()) } throws
                ResponseStatusException(HttpStatus.NOT_FOUND, "no recorded builds")
            every { branchPermalinks.latestGreenBuild("main") } returns greenBuild
        }

        test("serves an html artifact with no-cache headers") {
            Files.writeString(artifactDir.resolve("index.html"), "<html>report</html>")

            mockMvc
                .perform(get("/artifacts/known-key/index.html"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", "text/html"))
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
                .andExpect(content().string("<html>report</html>"))
        }

        test("serves a log file as UTF-8 text with no-cache headers") {
            Files.writeString(artifactDir.resolve("build.log"), "line one")

            mockMvc
                .perform(get("/artifacts/known-key/build.log"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
        }

        test("serves nested report files without no-cache headers") {
            val nested = Files.createDirectories(artifactDir.resolve("reports/tests"))
            Files.writeString(nested.resolve("summary.css"), "body {}")

            mockMvc
                .perform(get("/artifacts/known-key/reports/tests/summary.css"))
                .andExpect(status().isOk)
                .andExpect(header().doesNotExist("Cache-Control"))
        }

        test("unknown artifact key answers 404") {
            mockMvc
                .perform(get("/artifacts/unknown-key/index.html"))
                .andExpect(status().isNotFound)
        }

        test("missing file answers 404") {
            mockMvc
                .perform(get("/artifacts/known-key/no-such-file.html"))
                .andExpect(status().isNotFound)
        }

        test("path traversal out of the artifact directory is rejected") {
            val outside = Files.writeString(artifactDir.parent.resolve("outside.txt"), "secret")
            try {
                mockMvc
                    .perform(get("/artifacts/known-key/../outside.txt"))
                    .andExpect(status().is4xxClientError)
            } finally {
                Files.deleteIfExists(outside)
            }
        }

        test("permanent URL serves the file from the branch's latest green build") {
            Files.writeString(artifactDir.resolve("build.log"), "line one")

            mockMvc
                .perform(get("/branches/main/build.log"))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
                .andExpect(content().string("line one"))
        }

        test("permanent URL serves even normally cacheable files with no-store") {
            val nested = Files.createDirectories(artifactDir.resolve("reports/tests"))
            Files.writeString(nested.resolve("summary.css"), "body {}")

            mockMvc
                .perform(get("/branches/main/reports/tests/summary.css"))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
        }

        test("permanent directory URL with trailing slash serves the directory's index.html") {
            val reportDir = Files.createDirectories(artifactDir.resolve("reports/build/doc"))
            Files.writeString(reportDir.resolve("index.html"), "<html>doc</html>")

            mockMvc
                .perform(get("/branches/main/reports/build/doc/"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", "text/html"))
                .andExpect(content().string("<html>doc</html>"))
        }

        test("permanent directory URL without trailing slash redirects to the trailing-slash form") {
            val reportDir = Files.createDirectories(artifactDir.resolve("reports/build/doc"))
            Files.writeString(reportDir.resolve("index.html"), "<html>doc</html>")

            mockMvc
                .perform(get("/branches/main/reports/build/doc"))
                .andExpect(status().isFound)
                .andExpect(header().string("Location", "/branches/main/reports/build/doc/"))
                .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
        }

        test("directory URL serves the single page of an index-less report directory") {
            val profileDir = Files.createDirectories(artifactDir.resolve("reports/profile"))
            Files.writeString(profileDir.resolve("profile-2026-08-10-18-36-12.html"), "<html>profile</html>")

            mockMvc
                .perform(get("/branches/main/reports/profile/"))
                .andExpect(status().isOk)
                .andExpect(content().string("<html>profile</html>"))
            mockMvc
                .perform(get("/artifacts/known-key/reports/profile/"))
                .andExpect(status().isOk)
                .andExpect(content().string("<html>profile</html>"))
        }

        test("directory URL of an index-less report directory holding several pages answers 404") {
            val pmdDir = Files.createDirectories(artifactDir.resolve("reports/pmd"))
            Files.writeString(pmdDir.resolve("main.html"), "<html>main</html>")
            Files.writeString(pmdDir.resolve("test.html"), "<html>test</html>")

            mockMvc
                .perform(get("/branches/main/reports/pmd/"))
                .andExpect(status().isNotFound)
        }

        test("permanent URL with a bare trailing slash redirects to the artifact index page") {
            mockMvc
                .perform(get("/branches/main/"))
                .andExpect(status().isFound)
                .andExpect(header().string("Location", "/branches/main"))
        }

        test("permanent URL of an unknown branch key answers 404") {
            mockMvc
                .perform(get("/branches/no-such-branch/build.log"))
                .andExpect(status().isNotFound)
        }

        test("permanent URL answers 404 when the green build's artifacts are gone") {
            every { branchPermalinks.latestGreenBuild("main") } returns greenBuild.copy(artifactKey = "pruned-key")

            mockMvc
                .perform(get("/branches/main/build.log"))
                .andExpect(status().isNotFound)
        }

        test("permanent URL rejects path traversal out of the artifact directory") {
            val outside = Files.writeString(artifactDir.parent.resolve("outside.txt"), "secret")
            try {
                mockMvc
                    .perform(get("/branches/main/../outside.txt"))
                    .andExpect(status().is4xxClientError)
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }
}
