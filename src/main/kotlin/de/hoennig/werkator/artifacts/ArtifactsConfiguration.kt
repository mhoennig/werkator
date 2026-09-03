package de.hoennig.werkator.artifacts

import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.repo.RepoContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ArtifactsConfiguration {
    /** The current repository's artifact store, for the code paths that still take the store bean. */
    @Bean
    fun artifactStore(repo: RepoContext): ArtifactStore = repo.artifactStore
}
