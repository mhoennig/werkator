package de.hoennig.werkator.config

import java.time.Duration

/**
 * Parses durations in the format used by `watcher.newBranchMaxAge` and `watcher.pollInterval`:
 * `5d` (days), `12h` (hours), `10m` (minutes), or `30s` (seconds).
 */
object DurationParser {
    private val pattern = Regex("""(\d+)([dhms])""")

    fun parse(value: String): Duration {
        val match =
            pattern.matchEntire(value.trim())
                ?: throw IllegalArgumentException(
                    "invalid duration '$value': expected <amount>d, <amount>h, <amount>m, or <amount>s, e.g. 5d or 10s",
                )
        val (amount, unit) = match.destructured
        return when (unit) {
            "d" -> Duration.ofDays(amount.toLong())
            "h" -> Duration.ofHours(amount.toLong())
            "m" -> Duration.ofMinutes(amount.toLong())
            else -> Duration.ofSeconds(amount.toLong())
        }
    }
}
