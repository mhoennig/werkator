package de.hoennig.gittally.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration

class DurationParserTest : FunSpec() {
    init {
        test("parses days") {
            DurationParser.parse("5d") shouldBe Duration.ofDays(5)
        }

        test("parses hours") {
            DurationParser.parse("12h") shouldBe Duration.ofHours(12)
        }

        test("parses multi-digit amounts") {
            DurationParser.parse("120h") shouldBe Duration.ofHours(120)
        }

        test("parses zero") {
            DurationParser.parse("0d") shouldBe Duration.ZERO
        }

        test("tolerates surrounding whitespace") {
            DurationParser.parse(" 5d ") shouldBe Duration.ofDays(5)
        }

        test("rejects invalid values") {
            listOf("", "5", "d", "5x", "-5d", "5D", "1.5d", "5 d").forEach { value ->
                val exception = shouldThrow<IllegalArgumentException> { DurationParser.parse(value) }
                exception.message shouldContain "invalid duration"
            }
        }
    }
}
