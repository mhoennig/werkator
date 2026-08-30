package de.hoennig.werkator.watcher

import java.time.Instant

/** Observable watcher health for status endpoints (step 07) and the UI (step 08). */
data class WatcherState(
    /** Whether the poll loop is scheduled. */
    val running: Boolean = false,
    /** When the last poll cycle started, successful or not. */
    val lastPollAt: Instant? = null,
    /** Why the last `fetchOrigin` failed; null after a successful fetch. */
    val lastFetchError: String? = null,
    /** Why the last poll cycle crashed after a successful fetch; null after a clean cycle. */
    val lastPollError: String? = null,
    /** Branches whose latest build was PENDING or RUNNING at the end of the last poll. */
    val queuedBranches: List<String> = emptyList(),
)
