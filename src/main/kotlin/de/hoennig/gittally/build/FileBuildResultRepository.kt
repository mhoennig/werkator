package de.hoennig.gittally.build

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Stores build results as a JSON file, e.g. `.git/gittally/build-results.json`.
 * Writes are atomic (temp file + atomic move) so readers never see partial content.
 */
class FileBuildResultRepository(
    private val file: Path,
) : BuildResultRepository {
    private val log = LoggerFactory.getLogger(FileBuildResultRepository::class.java)
    private val lock = Any()

    private val json =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)

    override fun append(result: BuildResult) {
        synchronized(lock) {
            save(load() + result)
        }
    }

    override fun updateLatest(
        branch: String,
        transform: (BuildResult) -> BuildResult,
    ): BuildResult? {
        synchronized(lock) {
            val results = load()
            val index = indexOfLatest(results, branch) ?: return null
            val updated = transform(results[index])
            save(results.toMutableList().also { it[index] = updated })
            return updated
        }
    }

    override fun updateByArtifactKey(
        artifactKey: String,
        transform: (BuildResult) -> BuildResult,
    ): BuildResult? {
        synchronized(lock) {
            val results = load()
            val index = results.indexOfLast { it.artifactKey == artifactKey }
            if (index < 0) {
                return null
            }
            val updated = transform(results[index])
            save(results.toMutableList().also { it[index] = updated })
            return updated
        }
    }

    override fun latestFor(branch: String): BuildResult? {
        val results = load()
        return indexOfLatest(results, branch)?.let { results[it] }
    }

    override fun latestGreenFor(branch: String): BuildResult? =
        load()
            .filter { it.branch == branch && it.status == BuildStatus.SUCCESS }
            .maxByOrNull { it.startedAt }

    override fun latestPerBranch(): List<BuildResult> =
        load()
            .groupBy { it.branch }
            .values
            .map { entries -> entries.reduce(::laterOf) }
            .sortedByDescending { it.startedAt }

    override fun history(): List<BuildResult> = load().sortedByDescending { it.startedAt }

    override fun delete(artifactKey: String): Boolean {
        synchronized(lock) {
            val results = load()
            val remaining = results.filterNot { it.artifactKey == artifactKey }
            if (remaining.size == results.size) {
                return false
            }
            save(remaining)
            return true
        }
    }

    override fun markStaleRunningAsInterrupted(): List<BuildResult> {
        synchronized(lock) {
            val results = load()
            val changed = mutableListOf<BuildResult>()
            val updated =
                results.map { result ->
                    val superseded =
                        results.any { it.branch == result.branch && it.startedAt.isAfter(result.startedAt) }
                    if (result.status == BuildStatus.RUNNING ||
                        (result.status == BuildStatus.PENDING && superseded)
                    ) {
                        result.copy(status = BuildStatus.INTERRUPTED).also { changed += it }
                    } else {
                        result
                    }
                }
            if (changed.isNotEmpty()) {
                save(updated)
            }
            return changed
        }
    }

    override fun prune(
        originBranches: Collection<String>,
        retentionPerBranch: Int,
        keepLatestGreen: Boolean,
    ): List<BuildResult> {
        synchronized(lock) {
            val results = load()
            val originBranchSet = originBranches.toSet()
            val kept =
                results
                    .filter { it.branch in originBranchSet }
                    .groupBy { it.branch }
                    .values
                    .flatMap { entries ->
                        val newest =
                            entries
                                .sortedByDescending { it.startedAt }
                                .take(retentionPerBranch.coerceAtLeast(0))
                        val latestGreen =
                            entries
                                .filter { keepLatestGreen && it.status == BuildStatus.SUCCESS }
                                .maxByOrNull { it.startedAt }
                        newest + listOfNotNull(latestGreen)
                    }.toSet()
            val removed = results.filterNot { it in kept }
            if (removed.isNotEmpty()) {
                save(results.filter { it in kept })
            }
            return removed
        }
    }

    /** The index of the newest entry of [branch]; on equal timestamps the later appended entry wins. */
    private fun indexOfLatest(
        results: List<BuildResult>,
        branch: String,
    ): Int? {
        var latest: Int? = null
        results.forEachIndexed { index, result ->
            if (result.branch == branch &&
                (latest == null || !result.startedAt.isBefore(results[latest].startedAt))
            ) {
                latest = index
            }
        }
        return latest
    }

    private fun laterOf(
        first: BuildResult,
        second: BuildResult,
    ): BuildResult = if (second.startedAt.isBefore(first.startedAt)) first else second

    private fun load(): List<BuildResult> {
        if (!Files.exists(file)) {
            return emptyList()
        }
        return try {
            json.readValue<List<BuildResult>>(file.toFile())
        } catch (e: Exception) {
            log.warn("ignoring unreadable build results file {}: {}", file, e.message)
            emptyList()
        }
    }

    private fun save(results: List<BuildResult>) {
        Files.createDirectories(file.parent)
        val tempFile = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            json.writeValue(tempFile.toFile(), results)
            Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
