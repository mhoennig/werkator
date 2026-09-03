package de.hoennig.werkator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * A version bump is easy to forget on a deployment (it happened twice on 2026-09-03) — this
 * fails the build so a deployment can never carry an artifact whose footer/`--version` claims
 * a release that has no notes, or notes for a release nothing was actually built for.
 */
class ReleaseVersionConsistencyTest : FunSpec() {
    init {
        test("the top release-notes entry names the current project version") {
            val buildGradle = File("build.gradle.kts").readText()
            val versionMatch = Regex("""^version = "([^"]+)"""", RegexOption.MULTILINE).find(buildGradle)
            versionMatch shouldNotBe null
            val projectVersion = versionMatch!!.groupValues[1]

            val releaseNotes = File("src/main/resources/templates/releases.html").readText()
            val topHeadingMatch = Regex("""<h2>v([0-9.]+)\s""").find(releaseNotes)
            topHeadingMatch shouldNotBe null
            val topReleasedVersion = topHeadingMatch!!.groupValues[1]

            topReleasedVersion shouldBe projectVersion
        }
    }
}
