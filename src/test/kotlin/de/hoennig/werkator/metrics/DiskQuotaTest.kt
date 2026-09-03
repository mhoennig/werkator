package de.hoennig.werkator.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** `quota -u -g --no-wrap --raw-grace` on mih09, 2026-09-03 (the PR#16 attachment); the parser fixture. */
private val MIH09_QUOTA_OUTPUT =
    """
    Disk quotas for user mih09-werkator (uid 120974): none
    Disk quotas for group mih09 (gid 102180):
         Filesystem  blocks   quota   limit   grace   files   quota   limit   grace
    /dev/disk/by-id/wwn-0x0000000000000001-part2 1088116  8388608 12582912       0   14226  16777216 25165824       0
          /dev/sdb1 1456376  10485760 15728640       0   52983  20971520 31457280       0
    """.trimIndent()

class DiskQuotaTest : FunSpec() {
    init {
        test("the mih09 output parses into one group line per filesystem and no user line") {
            val lines = DiskQuota.parse(MIH09_QUOTA_OUTPUT)

            lines shouldHaveSize 2
            lines.none { it.kind == "user" } shouldBe true
            lines[0] shouldBe
                QuotaLine(
                    kind = "group",
                    subject = "mih09",
                    filesystem = "/dev/disk/by-id/wwn-0x0000000000000001-part2",
                    blocksKib = 1_088_116,
                    softKib = 8_388_608,
                    hardKib = 12_582_912,
                )
            lines[1].filesystem shouldBe "/dev/sdb1"
        }

        test("no quota tooling, an empty output, and a 'none'-only output all parse to no lines") {
            DiskQuota.parse("").shouldBeEmpty()
            DiskQuota.parse("Disk quotas for user mih09-werkator (uid 120974): none").shouldBeEmpty()
            DiskQuota
                .parse(
                    """
                    Disk quotas for user mih09-werkator (uid 120974): none
                    Disk quotas for group mih09 (gid 102180): none
                    """.trimIndent(),
                ).shouldBeEmpty()
        }

        test("only the lines of the directory's file store are considered, matched exactly or by device name") {
            val lines = DiskQuota.parse(MIH09_QUOTA_OUTPUT)

            val exact = DiskQuota.candidatesFor(lines, "/dev/disk/by-id/wwn-0x0000000000000001-part2")
            exact shouldHaveSize 1
            exact[0].source.filesystem shouldBe "/dev/disk/by-id/wwn-0x0000000000000001-part2"

            val byDeviceName = DiskQuota.candidatesFor(lines, "/dev/sdb1")
            byDeviceName shouldHaveSize 1
            byDeviceName[0].source.filesystem shouldBe "/dev/sdb1"

            DiskQuota.candidatesFor(lines, "/dev/mapper/unrelated").shouldBeEmpty()
        }

        test("a group quota candidate reports soft limit as total, blocks as used, and the hard limit alongside") {
            val lines = DiskQuota.parse(MIH09_QUOTA_OUTPUT)

            val candidates = DiskQuota.candidatesFor(lines, "/dev/disk/by-id/wwn-0x0000000000000001-part2")

            val candidate = candidates.single()
            candidate.space.totalBytes shouldBe 8_388_608L * 1024
            candidate.space.usedBytes shouldBe 1_088_116L * 1024
            candidate.space.freeBytes shouldBe (8_388_608L - 1_088_116L) * 1024
            candidate.source.kind shouldBe "group"
            candidate.source.subject shouldBe "mih09"
            candidate.source.softLimitGib!! shouldBe (8_388_608.0 * 1024 / GIB)
            candidate.source.hardLimitGib!! shouldBe (12_582_912.0 * 1024 / GIB)
        }

        test("an over-quota '*' marker on the blocks field does not break parsing") {
            val output =
                """
                Disk quotas for group mih09 (gid 102180):
                     Filesystem  blocks   quota   limit   grace   files   quota   limit   grace
                          /dev/sda1 9000000*  8388608 12582912  604800   14226  16777216 25165824       0
                """.trimIndent()

            val lines = DiskQuota.parse(output)

            lines.single().blocksKib shouldBe 9_000_000
        }

        test("a line with only a hard limit uses it as the total; a line with neither is no candidate") {
            val output =
                """
                Disk quotas for group mih09 (gid 102180):
                     Filesystem  blocks   quota   limit   grace   files   quota   limit   grace
                          /dev/sda1 1000000  0 2000000       0   1   0   0       0
                          /dev/sdc1 1000000  0 0       0   1   0   0       0
                """.trimIndent()

            val lines = DiskQuota.parse(output)

            val hardOnly = DiskQuota.candidatesFor(lines, "/dev/sda1")
            hardOnly.single().space.totalBytes shouldBe 2_000_000L * 1024

            DiskQuota.candidatesFor(lines, "/dev/sdc1").shouldBeEmpty()
        }

        test("among user quota, group quota and volume the smallest headroom binds") {
            val userQuota =
                DiskCandidate(
                    space =
                        SystemMetricsCollector.DiskSpace(
                            totalBytes = 10L * GIB.toLong(),
                            usedBytes = 8L * GIB.toLong(),
                            freeBytes =
                                2L * GIB.toLong(),
                        ),
                    source = DiskSource(kind = "user", subject = "mih09-werkator", filesystem = "/dev/x"),
                )
            val groupQuota =
                DiskCandidate(
                    space =
                        SystemMetricsCollector.DiskSpace(
                            totalBytes = 15L * GIB.toLong(),
                            usedBytes = 8L * GIB.toLong(),
                            freeBytes =
                                7L * GIB.toLong(),
                        ),
                    source = DiskSource(kind = "group", subject = "mih09", filesystem = "/dev/x"),
                )
            val roomyVolume =
                DiskCandidate(
                    space =
                        SystemMetricsCollector.DiskSpace(
                            totalBytes = 71L * GIB.toLong(),
                            usedBytes = 37L * GIB.toLong(),
                            freeBytes =
                                34L * GIB.toLong(),
                        ),
                    source = DiskSource.volume(),
                )
            val tightVolume = roomyVolume.copy(space = roomyVolume.space.copy(freeBytes = 1L * GIB.toLong()))

            DiskQuota.bindingDiskSpace(listOf(userQuota, groupQuota, roomyVolume)).source.kind shouldBe "user"
            DiskQuota.bindingDiskSpace(listOf(groupQuota, roomyVolume)).source.kind shouldBe "group"
            DiskQuota.bindingDiskSpace(listOf(groupQuota, tightVolume)).source.kind shouldBe "volume"
        }
    }

    companion object {
        private const val GIB = 1_073_741_824.0
    }
}
