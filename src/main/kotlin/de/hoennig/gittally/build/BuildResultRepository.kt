package de.hoennig.gittally.build

import java.time.Instant

/**
 * Entries are grouped by [BuildResult.name] — the branch name, unless a named
 * auto-build slot records its builds under its own name. Every "latest", retention
 * pool, and green lookup works on that name; only the gone-from-origin pruning
 * looks at the underlying [BuildResult.branch].
 */
interface BuildResultRepository {
    fun append(result: BuildResult)

    /** Applies [transform] to the newest entry recorded under [name]; returns null if there is none. */
    fun updateLatest(
        name: String,
        transform: (BuildResult) -> BuildResult,
    ): BuildResult?

    /** Applies [transform] to the entry with [artifactKey]; returns null if no entry matches. */
    fun updateByArtifactKey(
        artifactKey: String,
        transform: (BuildResult) -> BuildResult,
    ): BuildResult?

    fun latestFor(name: String): BuildResult?

    /** The newest SUCCESS entry recorded under [name] — the build behind the permanent `/branches/…` links. */
    fun latestGreenFor(name: String): BuildResult?

    /** The newest entry of each build name, newest first. */
    fun latestPerName(): List<BuildResult>

    /** All entries, newest first. */
    fun history(): List<BuildResult>

    /** Removes all entries with the given artifact key; returns true if anything was removed. */
    fun delete(artifactKey: String): Boolean

    /**
     * Startup recovery: RUNNING entries and PENDING entries superseded by a newer entry
     * of the same build name become INTERRUPTED. Returns the changed entries.
     */
    fun markStaleRunningAsInterrupted(): List<BuildResult>

    /**
     * Keeps the newest [retentionPerBranch] entries per build name and drops entries whose
     * branch is not contained in [originBranches]. With [retentionCutoff], entries started
     * before the cutoff are dropped even within the retention count — except each name's
     * newest entry, so dormant branches keep their last status. With [keepLatestGreen], the
     * newest SUCCESS entry of each surviving name is kept even beyond both limits, so the
     * permanent `/branches/…` artifact links stay valid while newer builds fail.
     * PENDING and RUNNING entries are never removed, regardless of all limits and even
     * when their branch is gone from [originBranches] — a queued or executing build
     * belongs to the executor, and pruning its result would make it invisible in UI
     * and history. Returns the removed entries.
     */
    fun prune(
        originBranches: Collection<String>,
        retentionPerBranch: Int,
        keepLatestGreen: Boolean = false,
        retentionCutoff: Instant? = null,
    ): List<BuildResult>
}
