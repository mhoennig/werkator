package de.hoennig.werkator.repo

import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.build.BuildResultRepository
import java.nio.file.Path

/**
 * Everything Werkator needs to work on one repository (ADR 0009): its primary
 * checkout, and the state that already lives inside or is keyed by it — build
 * results in `.git/werkator/`, the artifact store keyed by the repository path.
 * Git access and config loading stay path-based services and take [workingDir].
 *
 * One instance exists per registered repository, and the instance itself is the
 * identity: the executor serializes builds per (context, branch), so two contexts
 * for the same directory would build it concurrently. Today there is exactly one,
 * the current working directory ([RepoConfiguration]); the registry of the next
 * session creates one per entry.
 */
class RepoContext(
    /** Short unique name for display and, once routes carry it, the route segment; defaults to the directory basename. */
    val name: String,
    /** The primary checkout; never built in, its `.git/werkator/` holds the repository's state. */
    val workingDir: Path,
    val results: BuildResultRepository,
    val artifactStore: ArtifactStore,
) {
    override fun toString(): String = "RepoContext($name at $workingDir)"
}
