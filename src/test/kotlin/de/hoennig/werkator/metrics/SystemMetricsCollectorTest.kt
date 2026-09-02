package de.hoennig.werkator.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SystemMetricsCollectorTest : FunSpec() {
    private lateinit var tempDir: Path

    private val now = Instant.parse("2026-07-07T10:00:00Z")

    private fun collector(
        cpuCount: Int = 4,
        diskSpace: (Path) -> SystemMetricsCollector.DiskSpace = { GIB_100_DISK },
        repoSizeBytes: (Path) -> Long = { HALF_GIB_BYTES },
    ) = SystemMetricsCollector(
        stateFile = { tempDir.resolve("system-metrics-state.json") },
        repoDirs = { listOf(tempDir) },
        clock = Clock.fixed(now, ZoneOffset.UTC),
        procStat = tempDir.resolve("stat"),
        procMeminfo = tempDir.resolve("meminfo"),
        cpuCount = cpuCount,
        diskSpace = diskSpace,
        repoSizeBytes = repoSizeBytes,
    )

    private fun writeStat(
        total: Long,
        idle: Long,
    ) {
        // "cpu" totals across: user nice system idle iowait irq softirq steal guest guest_nice
        val user = total - idle
        Files.writeString(tempDir.resolve("stat"), "cpu  $user 0 0 $idle 0 0 0 0 0 0\ncpu0 0 0 0 0 0 0 0 0 0 0\n")
    }

    private fun writeMeminfo(
        totalKib: Long,
        availableKib: Long,
    ) {
        Files.writeString(
            tempDir.resolve("meminfo"),
            "MemTotal:       $totalKib kB\nMemFree:         1000000 kB\nMemAvailable:   $availableKib kB\n",
        )
    }

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("werkator-metrics-test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        test("CPU load is computed from /proc/stat deltas, so the first sample has no CPU metric yet") {
            writeStat(total = 1000, idle = 700)
            writeMeminfo(totalKib = 33_554_432, availableKib = 25_165_824)
            val collector = collector()

            collector.sample()
            collector.snapshot().cpuUsed shouldBe null

            writeStat(total = 1100, idle = 775)
            collector.sample()
            val snapshot = collector.snapshot()
            // 4 cores * (100 total - 75 idle) / 100 total = 1 core used
            snapshot.cpuUsed.shouldNotBeNull().current shouldBe 1.0
            snapshot.cpuIdle.shouldNotBeNull().current shouldBe 3.0
        }

        test("RAM comes from MemTotal and MemAvailable in GiB") {
            writeStat(total = 1000, idle = 700)
            writeMeminfo(totalKib = 33_554_432, availableKib = 25_165_824)
            val collector = collector()

            collector.sample()

            val snapshot = collector.snapshot()
            snapshot.ramTotalGib shouldBe 32.0
            snapshot.ramUsedGib.shouldNotBeNull().current shouldBe 8.0
            snapshot.ramFreeGib.shouldNotBeNull().current shouldBe 24.0
        }

        test("disk and repository size are reported in GiB") {
            writeStat(total = 1000, idle = 700)
            writeMeminfo(totalKib = 33_554_432, availableKib = 25_165_824)
            val collector = collector()

            collector.sample()

            val snapshot = collector.snapshot()
            snapshot.diskTotalGib shouldBe 100.0
            snapshot.diskUsedGib.shouldNotBeNull().current shouldBe 40.0
            snapshot.diskFreeGib.shouldNotBeNull().current shouldBe 55.0
            snapshot.repoSizeGib.shouldNotBeNull().current shouldBe 0.5
        }

        test("min, max, and avg aggregate over all samples") {
            writeStat(total = 1000, idle = 700)
            writeMeminfo(totalKib = 33_554_432, availableKib = 25_165_824)
            val usedGibValues = mutableListOf(40L, 20L, 60L)
            val collector =
                collector(diskSpace = {
                    SystemMetricsCollector.DiskSpace(
                        totalBytes = 100L * GIB,
                        usedBytes = usedGibValues.removeFirst() * GIB,
                        freeBytes = 30L * GIB,
                    )
                })

            repeat(3) { collector.sample() }

            val diskUsed = collector.snapshot().diskUsedGib.shouldNotBeNull()
            diskUsed.current shouldBe 60.0
            diskUsed.min shouldBe 20.0
            diskUsed.max shouldBe 60.0
            diskUsed.avg shouldBe 40.0
        }

        test("a restart loads the persisted aggregation state and continues the series") {
            writeStat(total = 1000, idle = 700)
            writeMeminfo(totalKib = 33_554_432, availableKib = 25_165_824)
            collector(repoSizeBytes = { 4L * GIB }).sample()

            val restarted = collector(repoSizeBytes = { 2L * GIB })
            restarted.sample()

            val snapshot = restarted.snapshot()
            snapshot.sampleCount shouldBe 2
            val repoSize = snapshot.repoSizeGib.shouldNotBeNull()
            repoSize.current shouldBe 2.0
            repoSize.min shouldBe 2.0
            repoSize.max shouldBe 4.0
            repoSize.avg shouldBe 3.0
        }

        test("a corrupt state file starts a fresh series instead of failing") {
            writeStat(total = 1000, idle = 700)
            writeMeminfo(totalKib = 33_554_432, availableKib = 25_165_824)
            Files.writeString(tempDir.resolve("system-metrics-state.json"), "not json {")

            val collector = collector()
            collector.sample()

            collector.snapshot().sampleCount shouldBe 1
        }

        test("unreadable sources degrade to null metrics, never fail the sample") {
            // no stat/meminfo files written, disk and repo-size probes fail
            val collector =
                collector(
                    diskSpace = { error("no file store") },
                    repoSizeBytes = { error("walk failed") },
                )

            collector.sample()

            val snapshot = collector.snapshot()
            snapshot.timestamp shouldBe now
            snapshot.sampleCount shouldBe 1
            snapshot.cpuCount shouldBe 4
            snapshot.cpuUsed shouldBe null
            snapshot.ramTotalGib shouldBe null
            snapshot.ramUsedGib shouldBe null
            snapshot.diskTotalGib shouldBe null
            snapshot.diskUsedGib shouldBe null
            snapshot.repoSizeGib shouldBe null
        }

        test("the repository size probe is throttled to every 10th sample") {
            writeStat(total = 1000, idle = 700)
            writeMeminfo(totalKib = 33_554_432, availableKib = 25_165_824)
            var probes = 0
            val collector =
                collector(repoSizeBytes = {
                    probes++
                    HALF_GIB_BYTES
                })

            repeat(11) { collector.sample() }

            probes shouldBe 2
            collector
                .snapshot()
                .repoSizeGib
                .shouldNotBeNull()
                .current shouldBe 0.5
        }

        test("the snapshot before the first sample is empty but well-formed") {
            val snapshot = collector().snapshot()

            snapshot.timestamp shouldBe null
            snapshot.sampleCount shouldBe 0
            snapshot.cpuCount shouldBe 4
            snapshot.cpuUsed shouldBe null
        }
    }

    companion object {
        private const val GIB = 1_073_741_824L

        private const val HALF_GIB_BYTES = GIB / 2

        private val GIB_100_DISK =
            SystemMetricsCollector.DiskSpace(
                totalBytes = 100L * GIB,
                usedBytes = 40L * GIB,
                freeBytes = 55L * GIB,
            )
    }
}
