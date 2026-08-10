package de.hoennig.gittally.server

import de.hoennig.gittally.config.GiteaConfig
import de.hoennig.gittally.metrics.MetricAggregate
import de.hoennig.gittally.metrics.SystemMetrics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class UiViewsTest : FunSpec() {
    init {
        test("durations format as m:ss and h:mm:ss like the legacy duration column") {
            UiFormats.duration(null) shouldBe ""
            UiFormats.duration(Duration.ofSeconds(0)) shouldBe "0:00"
            UiFormats.duration(Duration.ofSeconds(83)) shouldBe "1:23"
            UiFormats.duration(Duration.ofSeconds(3600 + 62)) shouldBe "1:01:02"
        }

        test("metric values format with two decimals and a dot, n/a when unavailable") {
            UiFormats.metric(null) shouldBe "n/a"
            UiFormats.metric(Double.NaN) shouldBe "n/a"
            UiFormats.metric(0.0) shouldBe "0.00"
            UiFormats.metric(1.234) shouldBe "1.23"
            UiFormats.metric(31.288) shouldBe "31.29"
        }

        test("utilization highlights warn from 80% and crit from 90% of the total") {
            UiFormats.utilizationClass(7.9, 10.0) shouldBe ""
            UiFormats.utilizationClass(8.0, 10.0) shouldBe "metric-warn"
            UiFormats.utilizationClass(8.9, 10.0) shouldBe "metric-warn"
            UiFormats.utilizationClass(9.0, 10.0) shouldBe "metric-crit"
            UiFormats.utilizationClass(11.0, 10.0) shouldBe "metric-crit"
        }

        test("utilization highlighting is off when a value or the total is unavailable") {
            UiFormats.utilizationClass(null, 10.0) shouldBe ""
            UiFormats.utilizationClass(9.5, null) shouldBe ""
            UiFormats.utilizationClass(Double.NaN, 10.0) shouldBe ""
            UiFormats.utilizationClass(9.5, 0.0) shouldBe ""
        }

        test("only the used rows with a total get the critical highlighting") {
            val aggregate = { value: Double -> MetricAggregate(current = value, min = 0.0, max = value, avg = value) }
            val view =
                SystemMetricsView.from(
                    SystemMetrics(
                        timestamp = Instant.parse("2026-08-10T12:00:00Z"),
                        sampleCount = 1,
                        cpuCount = 4,
                        ramTotalGib = 8.0,
                        diskTotalGib = 100.0,
                        cpuUsed = aggregate(3.7),
                        cpuIdle = aggregate(0.3),
                        ramUsedGib = aggregate(6.5),
                        ramFreeGib = aggregate(1.5),
                        diskUsedGib = aggregate(70.0),
                        diskFreeGib = aggregate(30.0),
                        repoSizeGib = aggregate(95.0),
                    ),
                )

            val classesByKey = view.rows.associate { it.key to it.currentClass }
            classesByKey["cpuUsed"] shouldBe "metric-crit"
            classesByKey["ramUsedGib"] shouldBe "metric-warn"
            classesByKey["diskUsedGib"] shouldBe ""
            // free/idle and the repo size have no meaningful utilization ratio
            classesByKey["cpuIdle"] shouldBe ""
            classesByKey["ramFreeGib"] shouldBe ""
            classesByKey["diskFreeGib"] shouldBe ""
            classesByKey["repoSizeGib"] shouldBe ""
        }

        test("Gitea web links escape branch segments but keep slashes") {
            val links =
                GiteaWebLinks(GiteaConfig(baseUrl = "https://git.example.org/", owner = "acme", repo = "widget"))

            links.repoUrl shouldBe "https://git.example.org/acme/widget"
            links.branchUrl("feature/some topic") shouldBe
                "https://git.example.org/acme/widget/src/branch/feature/some%20topic"
            links.commitUrl("0123abc") shouldBe "https://git.example.org/acme/widget/commit/0123abc"
        }

        test("Gitea web links are null when Gitea is not configured") {
            val links = GiteaWebLinks(GiteaConfig())

            links.repoUrl shouldBe null
            links.branchUrl("main") shouldBe null
            links.commitUrl("0123abc") shouldBe null
        }

        test("rows are in progress while running or pending, so the artifact link shows the log-only icon") {
            val links = GiteaWebLinks(GiteaConfig())

            fun row(status: String) =
                BuildRowView.from(
                    BranchDto(
                        branch = "main",
                        commit = "0123abc",
                        status = status,
                        startedAt = null,
                        durationSeconds = null,
                        artifactKey = "some-key",
                    ),
                    links,
                )

            row("running").inProgress shouldBe true
            row("pending").inProgress shouldBe true
            row("success").inProgress shouldBe false
            row("failed").inProgress shouldBe false
        }
    }
}
