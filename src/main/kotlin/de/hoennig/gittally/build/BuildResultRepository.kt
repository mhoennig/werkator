package de.hoennig.gittally.build

interface BuildResultRepository {
    fun append(result: BuildResult)

    /** Applies [transform] to the newest entry of [branch]; returns null if the branch has no entries. */
    fun updateLatest(
        branch: String,
        transform: (BuildResult) -> BuildResult,
    ): BuildResult?

    /** Applies [transform] to the entry with [artifactKey]; returns null if no entry matches. */
    fun updateByArtifactKey(
        artifactKey: String,
        transform: (BuildResult) -> BuildResult,
    ): BuildResult?

    fun latestFor(branch: String): BuildResult?

    /** The newest entry of each branch, newest first. */
    fun latestPerBranch(): List<BuildResult>

    /** All entries, newest first. */
    fun history(): List<BuildResult>

    /** Removes all entries with the given artifact key; returns true if anything was removed. */
    fun delete(artifactKey: String): Boolean

    /**
     * Startup recovery: RUNNING entries and PENDING entries superseded by a newer entry
     * of the same branch become INTERRUPTED. Returns the changed entries.
     */
    fun markStaleRunningAsInterrupted(): List<BuildResult>

    /**
     * Keeps the newest [retentionPerBranch] entries per branch and drops entries of branches
     * not contained in [originBranches]. Returns the removed entries.
     */
    fun prune(
        originBranches: Collection<String>,
        retentionPerBranch: Int,
    ): List<BuildResult>
}
