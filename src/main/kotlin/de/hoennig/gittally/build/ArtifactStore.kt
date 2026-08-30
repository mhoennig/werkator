package de.hoennig.werkator.build

import java.nio.file.Path

/**
 * Persists the artifacts of finished builds (logs plus configured report directories)
 * and prunes them together with the result retention.
 * Implemented by `de.hoennig.werkator.artifacts.FileArtifactStore`.
 */
interface ArtifactStore {
    /**
     * Takes over the staging directory of the finished [build] (log files) plus the
     * configured `artifactDirs` from [workspace] and stores them under the build's
     * artifact key. [workspace] is null when the build crashed before its workspace
     * was prepared; only the logs are stored then.
     */
    fun persist(
        build: BuildResult,
        stagingDir: Path,
        workspace: Path?,
    )

    /**
     * Deletes all stored artifact directories whose keys are not in [keptResults];
     * call after repository retention pruning. Returns the removed artifact keys.
     */
    fun prune(keptResults: Collection<BuildResult>): List<String>

    /** The stored artifact directory for [artifactKey], or null if none exists. */
    fun artifactDir(artifactKey: String): Path?

    /**
     * The artifact root directory (which need not exist yet). Besides the stored
     * builds it hosts sibling state like the system-metrics aggregation — legacy
     * kept its `system_state.dat` in the artifact root, too.
     */
    fun rootDir(): Path
}
