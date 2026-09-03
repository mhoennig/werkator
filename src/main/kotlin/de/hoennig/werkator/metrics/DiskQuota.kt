package de.hoennig.werkator.metrics

/** One `blocks`/`quota`/`limit` line of `quota -u -g --no-wrap --raw-grace`, KiB throughout. */
data class QuotaLine(
    val kind: String,
    val subject: String,
    val filesystem: String,
    val blocksKib: Long,
    val softKib: Long,
    val hardKib: Long,
)

/**
 * Where the binding [SystemMetricsCollector.DiskSpace] came from: the volume itself, or a
 * user/group quota. `kind` is `"volume"`, `"user"` or `"group"`; `subject`/`filesystem`/the
 * limits are set only for a quota. Serialized as-is into `GET /api/system`.
 */
data class DiskSource(
    val kind: String,
    val subject: String? = null,
    val filesystem: String? = null,
    val softLimitGib: Double? = null,
    val hardLimitGib: Double? = null,
) {
    companion object {
        fun volume() = DiskSource(kind = "volume")
    }
}

/** One candidate budget for a directory: a quota line or the volume, each with its source. */
data class DiskCandidate(
    val space: SystemMetricsCollector.DiskSpace,
    val source: DiskSource,
)

/**
 * Parses `quota -u -g --no-wrap --raw-grace` and picks the tightest of user quota, group
 * quota, and the volume — pure functions over strings and numbers, see PR#16.
 */
object DiskQuota {
    private val subjectHeader = Regex("""^Disk quotas for (user|group) (\S+) \([ug]id \d+\):\s*(none)?\s*$""")

    /** `null`/blank output, and a subject reported `none`, both yield no line for that subject. */
    fun parse(output: String): List<QuotaLine> {
        val lines = mutableListOf<QuotaLine>()
        var kind: String? = null
        var subject: String? = null
        for (rawLine in output.lines()) {
            val header = subjectHeader.find(rawLine.trimEnd())
            if (header != null) {
                kind = header.groupValues[1]
                subject = header.groupValues[2]
                continue
            }
            val currentKind = kind ?: continue
            val currentSubject = subject ?: continue
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Filesystem")) {
                continue
            }
            val fields = trimmed.split(Regex("\\s+"))
            if (fields.size < 4) {
                continue
            }
            val blocksKib = fields[1].trimEnd('*').toLongOrNull() ?: continue
            val softKib = fields[2].toLongOrNull() ?: continue
            val hardKib = fields[3].toLongOrNull() ?: continue
            lines +=
                QuotaLine(
                    kind = currentKind,
                    subject = currentSubject,
                    filesystem = fields[0],
                    blocksKib = blocksKib,
                    softKib = softKib,
                    hardKib = hardKib,
                )
        }
        return lines
    }

    /**
     * The lines whose filesystem matches [directoryFilesystem] — exactly, or by the last path
     * segment when one side is a resolved device path (`/dev/sdb1` vs `/dev/disk/by-id/…`) — each
     * turned into a candidate. A line with no soft and no hard limit (both zero) is no candidate;
     * a line with only a hard limit uses it as the total.
     */
    fun candidatesFor(
        lines: List<QuotaLine>,
        directoryFilesystem: String,
    ): List<DiskCandidate> =
        lines
            .filter { matchesFilesystem(it.filesystem, directoryFilesystem) }
            .mapNotNull { line ->
                val softBytes = line.softKib * BYTES_PER_KIB
                val hardBytes = line.hardKib * BYTES_PER_KIB
                val totalBytes = if (softBytes > 0) softBytes else hardBytes
                if (totalBytes <= 0) {
                    return@mapNotNull null
                }
                val usedBytes = line.blocksKib * BYTES_PER_KIB
                val freeBytes = maxOf(0L, totalBytes - usedBytes)
                DiskCandidate(
                    space =
                        SystemMetricsCollector.DiskSpace(
                            totalBytes = totalBytes,
                            usedBytes = usedBytes,
                            freeBytes = freeBytes,
                        ),
                    source =
                        DiskSource(
                            kind = line.kind,
                            subject = line.subject,
                            filesystem = line.filesystem,
                            softLimitGib = softBytes / BYTES_PER_GIB,
                            hardLimitGib = hardBytes / BYTES_PER_GIB,
                        ),
                )
            }

    private fun matchesFilesystem(
        quotaFilesystem: String,
        directoryFilesystem: String,
    ): Boolean =
        quotaFilesystem == directoryFilesystem ||
            quotaFilesystem.substringAfterLast('/') == directoryFilesystem.substringAfterLast('/')

    /** The candidate with the smallest headroom; the tightest budget always wins. */
    fun bindingDiskSpace(candidates: List<DiskCandidate>): DiskCandidate = candidates.minBy { it.space.freeBytes }

    private const val BYTES_PER_KIB = 1024L

    private const val BYTES_PER_GIB = 1_073_741_824.0
}
