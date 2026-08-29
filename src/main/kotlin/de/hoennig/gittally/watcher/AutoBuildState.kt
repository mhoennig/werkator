package de.hoennig.gittally.watcher

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * One recorded scheduled-build trigger: the result pool [branch] (a branch, or
 * `<branch>@<build>` of a named build definition) was enqueued for the [slot]
 * (UTC `HH:MM`) of [date] (ISO). The field keeps its legacy name `branch` so the
 * state file stays readable across versions.
 */
data class AutoBuildTrigger(
    val branch: String,
    val date: String,
    val slot: String,
)

/** Scheduled-build time slot matching (UTC `HH:MM`), like legacy `auto_build_check`. */
object AutoBuildSlots {
    private val log = LoggerFactory.getLogger(AutoBuildSlots::class.java)

    /**
     * The latest valid slot at or before [now], or null when no slot is due yet today.
     * The returned slot is always a concrete `HH:MM` — an hourly pattern is expanded
     * first, so each of its hours triggers separately.
     */
    fun latestDueSlot(
        times: List<String>,
        now: LocalTime,
    ): String? =
        times
            .flatMap { expand(it) }
            .mapNotNull { slot ->
                try {
                    LocalTime.parse(slot) to slot
                } catch (_: DateTimeParseException) {
                    log.warn("skipping invalid scheduled-build time slot '{}': expected HH:MM or ??:MM", slot)
                    null
                }
            }.filter { (parsed, _) -> !parsed.isAfter(now) }
            .maxByOrNull { (parsed, _) -> parsed }
            ?.second

    /** `??:MM` means every hour at that minute and expands to its 24 slots; `HH:MM` is itself. */
    private fun expand(time: String): List<String> {
        val slot = time.trim()
        if (!slot.startsWith("??:")) {
            return listOf(slot)
        }
        val minute = slot.substringAfter(':').toIntOrNull()
        if (minute == null || minute !in 0..59) {
            log.warn("skipping invalid scheduled-build time slot '{}': expected ??:MM with MM from 00 to 59", slot)
            return emptyList()
        }
        return (0..23).map { hour -> "%02d:%02d".format(hour, minute) }
    }
}

/**
 * Persists which auto-build slots already triggered as a JSON file,
 * e.g. `.git/gittally/auto-builds.json` (replaces the legacy `auto-builds.tsv`).
 * Entries of past days are dropped on write, so the file never grows unbounded.
 */
class FileAutoBuildState(
    private val file: Path,
) {
    private val log = LoggerFactory.getLogger(FileAutoBuildState::class.java)

    private val json =
        ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)

    fun isTriggered(
        branch: String,
        date: LocalDate,
        slot: String,
    ): Boolean = AutoBuildTrigger(branch, date.toString(), slot) in load()

    fun markTriggered(
        branch: String,
        date: LocalDate,
        slot: String,
    ) {
        val current = load().filter { it.date == date.toString() }
        save(current + AutoBuildTrigger(branch, date.toString(), slot))
    }

    private fun load(): List<AutoBuildTrigger> {
        if (!Files.exists(file)) {
            return emptyList()
        }
        return try {
            json.readValue<List<AutoBuildTrigger>>(file.toFile())
        } catch (e: Exception) {
            log.warn("ignoring unreadable auto-builds file {}: {}", file, e.message)
            emptyList()
        }
    }

    private fun save(triggers: List<AutoBuildTrigger>) {
        Files.createDirectories(file.parent)
        val tempFile = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            json.writeValue(tempFile.toFile(), triggers)
            Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
