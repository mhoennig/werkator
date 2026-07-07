package de.hoennig.gittally.config

import java.time.Duration

/** Parses durations in the `watcher.newBranchMaxAge` format: `5d` (days) or `12h` (hours). */
object DurationParser {
    private val pattern = Regex("""(\d+)([dh])""")

    fun parse(value: String): Duration {
        val match =
            pattern.matchEntire(value.trim())
                ?: throw IllegalArgumentException("invalid duration '$value': expected <amount>d or <amount>h, e.g. 5d or 12h")
        val (amount, unit) = match.destructured
        return when (unit) {
            "d" -> Duration.ofDays(amount.toLong())
            else -> Duration.ofHours(amount.toLong())
        }
    }
}
