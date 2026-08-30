package de.hoennig.werkator.artifacts

import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.config.ConfigLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ArtifactsConfiguration {
    /**
     * Store relative to the working directory, matching how `ConfigLoader` and the
     * `BuildResultRepository` bean resolve their files. Nothing is touched until the
     * first build persists, so the bean is safe outside a git repository.
     */
    @Bean
    fun artifactStore(configLoader: ConfigLoader): ArtifactStore = FileArtifactStore(configLoader)
}
