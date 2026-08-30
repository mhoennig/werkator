package de.hoennig.werkator.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.werkator.watcher.Watcher
import de.hoennig.werkator.watcher.WatcherState
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

@WebMvcTest(WatcherApiController::class, properties = ["spring.main.web-application-type=servlet"])
class WatcherApiControllerTest : FunSpec() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var watcher: Watcher

    init {
        beforeEach { clearMocks(watcher) }

        test("watcher health answers last poll and errors") {
            every { watcher.state() } returns
                WatcherState(
                    running = true,
                    lastPollAt = Instant.parse("2026-07-07T10:00:00Z"),
                    lastFetchError = "origin unreachable",
                    lastPollError = null,
                    queuedBranches = listOf("main"),
                )

            mockMvc
                .perform(get("/api/watcher"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.lastPollAt").value("2026-07-07T10:00:00Z"))
                .andExpect(jsonPath("$.lastFetchError").value("origin unreachable"))
                .andExpect(jsonPath("$.lastPollError").doesNotExist())
                .andExpect(jsonPath("$.queuedBranches[0]").value("main"))
        }
    }
}
