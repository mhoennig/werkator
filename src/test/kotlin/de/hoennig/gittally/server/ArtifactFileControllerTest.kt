package de.hoennig.gittally.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.gittally.build.ArtifactStore
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path

@WebMvcTest(ArtifactFileController::class, properties = ["spring.main.web-application-type=servlet"])
class ArtifactFileControllerTest : FunSpec() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var artifactStore: ArtifactStore

    private val artifactDir: Path = Files.createTempDirectory("gittally-artifact-serve-test")

    init {
        beforeEach {
            clearMocks(artifactStore)
            every { artifactStore.artifactDir(any()) } returns null
            every { artifactStore.artifactDir("known-key") } returns artifactDir
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
    }
}
