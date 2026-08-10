package de.hoennig.gittally.server

import de.hoennig.gittally.config.GiteaConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

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
