package de.hoennig.werkator.repo

import de.hoennig.werkator.StateDirMigration
import de.hoennig.werkator.artifacts.FileArtifactStore
import de.hoennig.werkator.build.FileBuildResultRepository
import de.hoennig.werkator.config.ConfigLoader
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Opens a [RepoContext] over a repository directory. Nothing is created until the first
 * build; only a pre-rename state directory is moved to its current name on the way, per
 * repository, before any path under it is resolved.
 */
@Component
class RepoContexts(
    private val configLoader: ConfigLoader,
) {
    fun open(
        workingDir: Path,
        name: String = defaultName(workingDir),
    ): RepoContext {
        StateDirMigration.migrateIfNeeded(workingDir)
        return RepoContext(
            name = name,
            workingDir = workingDir,
            results = FileBuildResultRepository(workingDir.resolve(RESULTS_FILE)),
            artifactStore = FileArtifactStore(configLoader, workingDir),
        )
    }

    companion object {
        /** Results file relative to the repository, next to the machine config in `.git/werkator/`. */
        const val RESULTS_FILE = ".git/werkator/build-results.json"

        /** The directory basename (ADR 0009); a filesystem root has none and falls back to a constant. */
        fun defaultName(workingDir: Path): String =
            workingDir
                .toAbsolutePath()
                .normalize()
                .fileName
                ?.toString()
                ?: "repository"
    }
}
