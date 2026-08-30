package de.hoennig.werkator.watcher

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime

class AutoBuildStateTest : FunSpec() {
    private fun stateFile(): Path = Files.createTempDirectory("werkator-autobuild-test").resolve("auto-builds.json")

    init {
        test("latestDueSlot picks the latest slot at or before now") {
            val times = listOf("01:00", "11:00", "13:00")

            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("00:59")).shouldBeNull()
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("01:00")) shouldBe "01:00"
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("12:00")) shouldBe "11:00"
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("23:59")) shouldBe "13:00"
        }

        test("latestDueSlot skips invalid slots but keeps the valid ones") {
            AutoBuildSlots.latestDueSlot(listOf("25:99", "nope", "02:00"), LocalTime.parse("12:00")) shouldBe "02:00"
            AutoBuildSlots.latestDueSlot(listOf("25:99"), LocalTime.parse("12:00")).shouldBeNull()
        }

        test("an hourly ??:MM slot is due every hour and resolves to that hour's concrete slot") {
            val times = listOf("??:05")

            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("00:04")).shouldBeNull()
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("00:05")) shouldBe "00:05"
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("07:30")) shouldBe "07:05"
            // the next hour is a different slot, so it triggers again
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("08:05")) shouldBe "08:05"
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("23:59")) shouldBe "23:05"
        }

        test("hourly and fixed slots combine, the latest due one wins") {
            val times = listOf("??:05", "12:30")

            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("12:20")) shouldBe "12:05"
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("12:45")) shouldBe "12:30"
            AutoBuildSlots.latestDueSlot(times, LocalTime.parse("13:10")) shouldBe "13:05"
        }

        test("latestDueSlot skips an hourly slot with an impossible minute") {
            AutoBuildSlots.latestDueSlot(listOf("??:70"), LocalTime.parse("12:00")).shouldBeNull()
            AutoBuildSlots.latestDueSlot(listOf("??:xx", "02:00"), LocalTime.parse("12:00")) shouldBe "02:00"
        }

        test("latestDueSlot of an empty slot list is null") {
            AutoBuildSlots.latestDueSlot(emptyList(), LocalTime.parse("12:00")).shouldBeNull()
        }

        test("markTriggered records exactly the branch, day, and slot") {
            val state = FileAutoBuildState(stateFile())
            val today = LocalDate.parse("2026-07-07")

            state.isTriggered("main", today, "11:00").shouldBeFalse()
            state.markTriggered("main", today, "11:00")

            state.isTriggered("main", today, "11:00").shouldBeTrue()
            state.isTriggered("main", today, "13:00").shouldBeFalse()
            state.isTriggered("main", today.plusDays(1), "11:00").shouldBeFalse()
            state.isTriggered("other", today, "11:00").shouldBeFalse()
        }

        test("triggers persist across instances") {
            val file = stateFile()
            val today = LocalDate.parse("2026-07-07")
            FileAutoBuildState(file).markTriggered("main", today, "11:00")

            FileAutoBuildState(file).isTriggered("main", today, "11:00").shouldBeTrue()
        }

        test("entries of past days are dropped on write") {
            val file = stateFile()
            val state = FileAutoBuildState(file)
            val yesterday = LocalDate.parse("2026-07-06")
            val today = LocalDate.parse("2026-07-07")
            state.markTriggered("main", yesterday, "11:00")

            state.markTriggered("main", today, "01:00")

            state.isTriggered("main", yesterday, "11:00").shouldBeFalse()
            state.isTriggered("main", today, "01:00").shouldBeTrue()
        }

        test("an unreadable state file is treated as empty") {
            val file = stateFile()
            Files.createDirectories(file.parent)
            Files.writeString(file, "not json at all {")
            val state = FileAutoBuildState(file)

            state.isTriggered("main", LocalDate.parse("2026-07-07"), "11:00").shouldBeFalse()
            state.markTriggered("main", LocalDate.parse("2026-07-07"), "11:00")
            state.isTriggered("main", LocalDate.parse("2026-07-07"), "11:00").shouldBeTrue()
        }
    }
}
