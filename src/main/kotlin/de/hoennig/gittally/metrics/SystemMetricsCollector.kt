package de.hoennig.gittally.metrics

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The aggregation state persisted in the artifact root (the legacy `system_state.dat`, but as JSON). */
data class PersistedMetricsState(
    val sampleCount: Long = 0,
    val series: Map<String, MetricSeries> = emptyMap(),
)

/**
 * Samples CPU (from `/proc/stat` deltas), RAM (from `/proc/meminfo`), disk, and
 * repository size every 60 seconds and keeps running min/max/avg per metric.
 * The aggregation state is persisted, so restarts continue the series.
 * Every source degrades gracefully: an unreadable source makes its metric null
 * (shown as `n/a`), never fails a sample. Like the [de.hoennig.gittally.watcher.Watcher],
 * nothing is scheduled until [start] is called (server mode only).
 */
class SystemMetricsCollector(
    private val stateFile: () -> Path,
    private val workingDir: Path = Paths.get("."),
    private val clock: Clock = Clock.systemUTC(),
    private val procStat: Path = Paths.get("/proc/stat"),
    private val procMeminfo: Path = Paths.get("/proc/meminfo"),
    private val cpuCount: Int = Runtime.getRuntime().availableProcessors(),
    private val diskSpace: (Path) -> DiskSpace = { dir -> fileStoreDiskSpace(dir) },
    private val repoSizeBytes: (Path) -> Long = { dir -> directorySizeBytes(dir) },
) {
    /** Disk usage in bytes; used/free follow `df` semantics (free is what a user can still allocate). */
    data class DiskSpace(
        val totalBytes: Long,
        val usedBytes: Long,
        val freeBytes: Long,
    )

    private val log = LoggerFactory.getLogger(SystemMetricsCollector::class.java)

    private val json =
        ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)

    private var scheduler: ScheduledExecutorService? = null

    private var state: PersistedMetricsState? = null

    private var previousCpu: CpuCounters? = null

    private var lastRepoSizeGib: Double? = null

    private var samplesSinceRepoSizeProbe = 0

    private val warnedSources = mutableSetOf<String>()

    @Volatile
    private var snapshot: SystemMetrics? = null

    /** The latest sample, or an empty snapshot (all metrics `null`) before the first one. */
    fun snapshot(): SystemMetrics = snapshot ?: emptySnapshot()

    /** Schedules the fixed-delay sampling loop; the first sample runs immediately. */
    @Synchronized
    fun start() {
        check(scheduler == null) { "metrics collector is already running" }
        scheduler =
            Executors
                .newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "gittally-metrics").apply { isDaemon = true }
                }.also {
                    it.scheduleWithFixedDelay(::sampleSafely, 0, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS)
                }
    }

    @Synchronized
    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    /**
     * Takes one sample, updates the aggregates, and persists the aggregation state.
     * CPU needs a counter delta, so the very first sample after process start reports
     * no CPU metric yet (legacy aggregated a meaningless near-zero first delta instead).
     */
    @Synchronized
    fun sample() {
        val previousState = state ?: loadState()
        val series = previousState.series.toMutableMap()
        val sampleCount = previousState.sampleCount + 1

        fun record(
            key: String,
            value: Double?,
        ): MetricAggregate? {
            if (value == null) {
                return null
            }
            val updated = series[key]?.plus(value) ?: MetricSeries.of(value)
            series[key] = updated
            return updated.aggregate(value)
        }

        val cpu = readCpuLoad()
        val ram = readRam()
        val disk = readDisk()
        val repoSizeGib = readRepoSizeThrottled()
        snapshot =
            SystemMetrics(
                timestamp = clock.instant(),
                sampleCount = sampleCount,
                cpuCount = cpuCount,
                ramTotalGib = ram?.totalGib,
                diskTotalGib = disk?.totalBytes?.let { it / BYTES_PER_GIB },
                cpuUsed = record("cpuUsed", cpu?.usedCores),
                cpuIdle = record("cpuIdle", cpu?.idleCores),
                ramUsedGib = record("ramUsedGib", ram?.usedGib),
                ramFreeGib = record("ramFreeGib", ram?.freeGib),
                diskUsedGib = record("diskUsedGib", disk?.usedBytes?.let { it / BYTES_PER_GIB }),
                diskFreeGib = record("diskFreeGib", disk?.freeBytes?.let { it / BYTES_PER_GIB }),
                repoSizeGib = record("repoSizeGib", repoSizeGib),
            )
        state = PersistedMetricsState(sampleCount = sampleCount, series = series)
        saveState(state!!)
    }

    private fun sampleSafely() {
        try {
            sample()
        } catch (e: Exception) {
            log.error("metrics sample failed", e)
        }
    }

    private fun emptySnapshot() =
        SystemMetrics(
            timestamp = null,
            sampleCount = 0,
            cpuCount = cpuCount,
            ramTotalGib = null,
            diskTotalGib = null,
            cpuUsed = null,
            cpuIdle = null,
            ramUsedGib = null,
            ramFreeGib = null,
            diskUsedGib = null,
            diskFreeGib = null,
            repoSizeGib = null,
        )

    // ---- sources, each null when unavailable --------------------------------

    private data class CpuCounters(
        val total: Long,
        val idle: Long,
    )

    private data class CpuLoad(
        val usedCores: Double,
        val idleCores: Double,
    )

    /** Like the legacy monitor: used cores = `cpuCount * (totalDiff - idleDiff) / totalDiff`. */
    private fun readCpuLoad(): CpuLoad? {
        val counters = readCpuCounters() ?: return null
        val previous = previousCpu
        previousCpu = counters
        if (previous == null) {
            return null
        }
        val totalDiff = counters.total - previous.total
        val idleDiff = counters.idle - previous.idle
        val usedCores =
            if (totalDiff > 0) {
                cpuCount * (totalDiff - idleDiff).toDouble() / totalDiff
            } else {
                0.0
            }
        return CpuLoad(usedCores = usedCores, idleCores = cpuCount - usedCores)
    }

    /** The aggregate `cpu` line of `/proc/stat`: total is the sum of all fields, idle is the 4th. */
    private fun readCpuCounters(): CpuCounters? =
        readSource("cpu") {
            val fields =
                Files
                    .readAllLines(procStat)
                    .first { it.startsWith("cpu ") }
                    .split(Regex("\\s+"))
                    .drop(1)
                    .map { it.toLong() }
            CpuCounters(total = fields.sum(), idle = fields[3])
        }

    private data class RamUsage(
        val totalGib: Double,
        val usedGib: Double,
        val freeGib: Double,
    )

    /** `MemTotal` and `MemAvailable` (KiB) from `/proc/meminfo`; used = total - available. */
    private fun readRam(): RamUsage? =
        readSource("ram") {
            val lines = Files.readAllLines(procMeminfo)
            val totalKib = memValueKib(lines, "MemTotal")
            val availableKib = memValueKib(lines, "MemAvailable")
            RamUsage(
                totalGib = totalKib / KIB_PER_GIB,
                usedGib = (totalKib - availableKib) / KIB_PER_GIB,
                freeGib = availableKib / KIB_PER_GIB,
            )
        }

    private fun memValueKib(
        lines: List<String>,
        key: String,
    ): Long =
        lines
            .first { it.startsWith("$key:") }
            .removePrefix("$key:")
            .trim()
            .removeSuffix(" kB")
            .toLong()

    private fun readDisk(): DiskSpace? = readSource("disk") { diskSpace(workingDir) }

    /**
     * The repository size is expensive to determine (a full file walk), so unlike
     * legacy — which ran `du -sk` every cycle — it is re-probed only every
     * [REPO_SIZE_PROBE_EVERY_SAMPLES] samples and reused in between.
     */
    private fun readRepoSizeThrottled(): Double? {
        if (lastRepoSizeGib != null && samplesSinceRepoSizeProbe < REPO_SIZE_PROBE_EVERY_SAMPLES) {
            samplesSinceRepoSizeProbe++
            return lastRepoSizeGib
        }
        lastRepoSizeGib = readSource("repo size") { repoSizeBytes(workingDir) / BYTES_PER_GIB }
        samplesSinceRepoSizeProbe = 1
        return lastRepoSizeGib
    }

    /** Wraps one source read; a failure yields null and is logged once, not every 60s. */
    private fun <T> readSource(
        source: String,
        read: () -> T,
    ): T? =
        try {
            read().also { warnedSources.remove(source) }
        } catch (e: Exception) {
            if (warnedSources.add(source)) {
                log.warn("cannot read {} metrics; showing n/a: {}", source, e.toString())
            }
            null
        }

    // ---- aggregation state persistence ---------------------------------------

    private fun loadState(): PersistedMetricsState {
        val file = stateFile()
        if (!Files.exists(file)) {
            return PersistedMetricsState()
        }
        return try {
            json.readValue<PersistedMetricsState>(file.toFile())
        } catch (e: Exception) {
            log.warn("ignoring unreadable metrics state file {}; starting a fresh series: {}", file, e.message)
            PersistedMetricsState()
        }
    }

    private fun saveState(state: PersistedMetricsState) {
        try {
            val file = stateFile()
            Files.createDirectories(file.parent)
            val tempFile = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
            try {
                json.writeValue(tempFile.toFile(), state)
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        } catch (e: Exception) {
            log.warn("cannot persist metrics state; aggregates restart on the next server start: {}", e.toString())
        }
    }

    companion object {
        const val SAMPLE_INTERVAL_SECONDS = 60L

        /** With 60s samples, the repository size is re-measured every 10 minutes. */
        const val REPO_SIZE_PROBE_EVERY_SAMPLES = 10

        /** State file name in the artifact root, next to the `branches/` directory. */
        const val STATE_FILE_NAME = "system-metrics-state.json"

        private const val KIB_PER_GIB = 1_048_576.0

        private const val BYTES_PER_GIB = 1_073_741_824.0

        /** `df` semantics via [java.nio.file.FileStore]: free is the space a user can still allocate. */
        fun fileStoreDiskSpace(dir: Path): DiskSpace {
            val store = Files.getFileStore(dir)
            return DiskSpace(
                totalBytes = store.totalSpace,
                usedBytes = store.totalSpace - store.unallocatedSpace,
                freeBytes = store.usableSpace,
            )
        }

        /** File-walk replacement for the legacy `du -sk`; unreadable subtrees are skipped, links are not followed. */
        fun directorySizeBytes(dir: Path): Long {
            var size = 0L
            Files.walkFileTree(
                dir,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attrs.isRegularFile) {
                            size += attrs.size()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
            return size
        }
    }
}
