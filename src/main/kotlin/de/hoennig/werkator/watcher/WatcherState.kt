package de.hoennig.werkator.watcher

import java.time.Instant

/**
 * Observable watcher health for status endpoints (step 07) and the UI (step 08).
 * The top-level fields describe the whole poll cycle — with one repository they are
 * that repository's, with several they aggregate [repositories], where each served
 * repository reports on its own (ADR 0009).
 */
data class WatcherState(
    /** Whether the poll loop is scheduled. */
    val running: Boolean = false,
    /** When the last poll cycle started, successful or not. */
    val lastPollAt: Instant? = null,
    /** Why the last `fetchOrigin` failed; null after a successful fetch. With several repositories, `<name>: <reason>` per failure. */
    val lastFetchError: String? = null,
    /** Why the last poll cycle crashed after a successful fetch; null after a clean cycle. Named per repository like [lastFetchError]. */
    val lastPollError: String? = null,
    /** Branches whose latest build was PENDING or RUNNING at the end of the last poll, over all repositories. */
    val queuedBranches: List<String> = emptyList(),
    /** The same per served repository, in registry order. */
    val repositories: List<RepoWatcherState> = emptyList(),
)

/** One repository's part of the last poll cycle. */
data class RepoWatcherState(
    val name: String,
    val lastPollAt: Instant? = null,
    val lastFetchError: String? = null,
    val lastPollError: String? = null,
    val queuedBranches: List<String> = emptyList(),
)
