package de.hoennig.werkator.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ConfigVersionsTest : FunSpec() {
    private fun verdict(
        since: String = "",
        below: String = "",
        running: String?,
    ) = ConfigVersions.verdict(VersionRequirement(since = since, below = below), running)

    init {
        test("a file needing a newer Werkator is refused, naming both versions") {
            val result = verdict(since = "0.9.16", running = "0.9.15")

            result
                .shouldBeInstanceOf<VersionVerdict.Incompatible>()
                .message
                .let {
                    it shouldContain "0.9.16"
                    it shouldContain "0.9.15"
                }
        }

        test("the running version satisfies its own floor") {
            verdict(since = "0.9.16", running = "0.9.16") shouldBe VersionVerdict.Compatible
            verdict(since = "0.9.16", running = "0.10.0") shouldBe VersionVerdict.Compatible
            verdict(since = "1.2", running = "1.2.3") shouldBe VersionVerdict.Compatible
        }

        test("exceeding the declared ceiling only warns — an unmaintained marker must not stop a CI") {
            val result = verdict(since = "0.9.16", below = "2.0", running = "2.1.0")

            result.shouldBeInstanceOf<VersionVerdict.Warn>().message shouldContain "2.0"
            verdict(since = "0.9.16", below = "2.0", running = "1.9.9") shouldBe VersionVerdict.Compatible
        }

        test("a file written before a breaking change is refused once that version runs") {
            // the shipped constant is empty while no such change has happened
            val brokeIn = "2.0.0"
            val description = "`builds:` is now `buildSpec:`"
            val written14 = VersionRequirement(since = "1.4.0")

            // no ceiling declared, and none needed: Werkator knows its own breaking change
            ConfigVersions
                .verdict(written14, "2.0.1", brokeIn, description)
                .shouldBeInstanceOf<VersionVerdict.Incompatible>()
                .message
                .let {
                    it shouldContain "2.0.0"
                    it shouldContain "buildSpec"
                }
            // a Werkator from before the change still reads that file
            ConfigVersions.verdict(written14, "1.9.0", brokeIn, description) shouldBe VersionVerdict.Compatible
            // and a file written after the change is fine on both sides of it
            ConfigVersions.verdict(
                VersionRequirement(since = "2.0.0"),
                "2.3.0",
                brokeIn,
                description,
            ) shouldBe VersionVerdict.Compatible
        }

        test("a file that declares nothing is never refused, even across a breaking change") {
            ConfigVersions.verdict(VersionRequirement(), "2.0.1", "2.0.0", "x") shouldBe VersionVerdict.Compatible
        }

        test("an unparseable or absent version never makes a file look incompatible") {
            verdict(since = "not-a-version", running = "1.0.0") shouldBe VersionVerdict.Compatible
            verdict(since = "0.9.16", running = null) shouldBe VersionVerdict.Compatible
            verdict(since = "0.9.16", running = "dev") shouldBe VersionVerdict.Compatible
            verdict(running = "1.0.0") shouldBe VersionVerdict.Compatible
        }

        test("versions compare part by part, missing parts as zero, pre-release suffixes ignored") {
            ConfigVersions.parse("2.0") shouldBe listOf(2, 0, 0)
            ConfigVersions.parse("1") shouldBe listOf(1, 0, 0)
            ConfigVersions.parse("1.0.0-rc1") shouldBe listOf(1, 0, 0)
            ConfigVersions.parse("0.10.0") shouldBe listOf(0, 10, 0)
            ConfigVersions.parse("").shouldBeNull()
            ConfigVersions.parse(null).shouldBeNull()
            ConfigVersions.parse("1.x").shouldBeNull()
        }

        test("0.10 is newer than 0.9, so the floor is not compared as text") {
            verdict(since = "0.10.0", running = "0.9.16").shouldBeInstanceOf<VersionVerdict.Incompatible>()
            verdict(since = "0.9.16", running = "0.10.0") shouldBe VersionVerdict.Compatible
        }
    }
}
