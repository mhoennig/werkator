package de.hoennig.werkator.repo

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths

@Configuration
class RepoConfiguration {
    /**
     * The single-repository case: the current working directory, which is how every
     * CLI command and the server resolve their files. Only paths are computed here, so
     * the bean is safe outside a git repository.
     */
    @Bean
    fun currentRepo(repoContexts: RepoContexts): RepoContext = repoContexts.open(Paths.get("."))
}
