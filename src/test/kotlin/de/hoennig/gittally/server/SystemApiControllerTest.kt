package de.hoennig.gittally.server

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.gittally.metrics.MetricAggregate
import de.hoennig.gittally.metrics.SystemMetrics
import de.hoennig.gittally.metrics.SystemMetricsCollector
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import org.hamcrest.Matchers.nullValue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(SystemApiController::class, properties = ["spring.main.web-application-type=servlet"])
class SystemApiControllerTest : FunSpec() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var collector: SystemMetricsCollector

    private val emptySnapshot =
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

    init {
        beforeEach { clearMocks(collector) }

        test("the system endpoint answers snapshot and aggregates") {
            every { collector.snapshot() } returns
                emptySnapshot.copy(
                    timestamp = Instant.parse("2026-07-07T10:00:00Z"),
                    sampleCount = 5,
                    ramTotalGib = 32.0,
                    cpuUsed = MetricAggregate(current = 1.0, min = 0.5, max = 2.0, avg = 1.25),
                )

            mockMvc
                .perform(get("/api/system"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.timestamp").value("2026-07-07T10:00:00Z"))
                .andExpect(jsonPath("$.sampleCount").value(5))
                .andExpect(jsonPath("$.cpuCount").value(8))
                .andExpect(jsonPath("$.ramTotalGib").value(32.0))
                .andExpect(jsonPath("$.cpuUsed.current").value(1.0))
                .andExpect(jsonPath("$.cpuUsed.min").value(0.5))
                .andExpect(jsonPath("$.cpuUsed.max").value(2.0))
                .andExpect(jsonPath("$.cpuUsed.avg").value(1.25))
        }

        test("unavailable metrics are explicit nulls, and the endpoint still answers 200") {
            every { collector.snapshot() } returns emptySnapshot

            mockMvc
                .perform(get("/api/system"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.sampleCount").value(0))
                .andExpect(jsonPath("$.timestamp").value(nullValue()))
                .andExpect(jsonPath("$.cpuUsed").value(nullValue()))
                .andExpect(jsonPath("$.ramUsedGib").value(nullValue()))
                .andExpect(jsonPath("$.repoSizeGib").value(nullValue()))
        }
    }
}
