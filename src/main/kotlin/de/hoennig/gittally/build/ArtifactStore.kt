package de.hoennig.gittally.build

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Takes over the staging directory of a finished build.
 * The real store (naming, retention, serving) arrives in step 05.
 */
interface ArtifactStore {
    fun persist(
        build: BuildResult,
        stagingDir: Path,
    )
}

/** Placeholder until step 05: logs and leaves the staging directory untouched. */
@Component
class NoOpArtifactStore : ArtifactStore {
    private val log = LoggerFactory.getLogger(NoOpArtifactStore::class.java)

    override fun persist(
        build: BuildResult,
        stagingDir: Path,
    ) {
        log.info("artifact store not implemented yet; leaving build output of {} in {}", build.artifactKey, stagingDir)
    }
}
