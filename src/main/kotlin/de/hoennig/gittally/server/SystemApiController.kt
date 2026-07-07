package de.hoennig.gittally.server

import de.hoennig.gittally.metrics.SystemMetrics
import de.hoennig.gittally.metrics.SystemMetricsCollector
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SystemApiController(
    private val collector: SystemMetricsCollector,
) {
    /**
     * The current system snapshot plus min/max/avg aggregates (the legacy
     * `system.json`). Unavailable metrics are null, never an error.
     */
    @GetMapping("/api/system")
    fun system(): SystemMetrics = collector.snapshot()
}
