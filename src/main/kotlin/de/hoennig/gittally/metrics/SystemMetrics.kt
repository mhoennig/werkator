package de.hoennig.gittally.metrics

import java.time.Instant

/** Current value of one metric plus its min/max/avg over all recorded samples. */
data class MetricAggregate(
    val current: Double,
    val min: Double,
    val max: Double,
    val avg: Double,
)

/**
 * One system snapshot with aggregates, the payload of `GET /api/system`
 * (the legacy `system.json`, with camelCase names like the rest of the API).
 * A metric is null when its source is unavailable — e.g. no `/proc` outside
 * Linux — and the UI shows `n/a`; the endpoint itself works everywhere.
 */
data class SystemMetrics(
    val timestamp: Instant?,
    val sampleCount: Long,
    val cpuCount: Int,
    val ramTotalGib: Double?,
    val diskTotalGib: Double?,
    val cpuUsed: MetricAggregate?,
    val cpuIdle: MetricAggregate?,
    val ramUsedGib: MetricAggregate?,
    val ramFreeGib: MetricAggregate?,
    val diskUsedGib: MetricAggregate?,
    val diskFreeGib: MetricAggregate?,
    val repoSizeGib: MetricAggregate?,
)

/** Running aggregation of one metric; persisted so restarts continue the series. */
data class MetricSeries(
    val min: Double,
    val max: Double,
    val sum: Double,
    val count: Long,
) {
    operator fun plus(value: Double) =
        MetricSeries(
            min = minOf(min, value),
            max = maxOf(max, value),
            sum = sum + value,
            count = count + 1,
        )

    fun aggregate(current: Double) = MetricAggregate(current = current, min = min, max = max, avg = sum / count)

    companion object {
        fun of(value: Double) = MetricSeries(min = value, max = value, sum = value, count = 1)
    }
}
