package de.hoennig.werkator.build

import de.hoennig.werkator.repo.RepoContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class BuildConfiguration {
    /** The current repository's results, for the code paths that still take the repository bean. */
    @Bean
    fun buildResultRepository(repo: RepoContext): BuildResultRepository = repo.results
}
