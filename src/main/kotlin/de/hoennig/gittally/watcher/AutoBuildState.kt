package de.hoennig.gittally.watcher

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import de.hoennig.gittally.config.AutoBuildSlot
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** One recorded auto-build trigger: [branch] was enqueued for the [slot] (UTC `HH:MM`) of [date] (ISO). */
data class AutoBuildTrigger(
    val branch: String,
    val date: String,
    val slot: String,
)

/** Auto-build time slot matching (UTC `HH:MM`), like legacy `auto_build_check`. */
object AutoBuildSlots {
    private val log = LoggerFactory.getLogger(AutoBuildSlots::class.java)

    /** The latest valid slot at or before [now], or null when no slot is due yet today. */
    fun latestDueSlot(
        times: List<AutoBuildSlot>,
        now: LocalTime,
    ): AutoBuildSlot? =
        times
            .mapNotNull { slot ->
                try {
                    LocalTime.parse(slot.time.trim()) to slot
                } catch (_: DateTimeParseException) {
                    log.warn("skipping invalid auto-build time slot '{}': expected HH:MM", slot.time)
                    null
                }
            }.filter { (parsed, _) -> !parsed.isAfter(now) }
            .maxByOrNull { (parsed, _) -> parsed }
            ?.second
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
