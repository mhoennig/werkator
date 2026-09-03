package de.hoennig.werkator.repo

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RepoConfiguration {
    /**
     * The repository the unscoped code paths mean — the current working directory
     * when it is served, see [RepoRegistry.current]. Without a registry only paths are
     * computed here, so the bean is safe outside a git repository.
     */
    @Bean
    fun currentRepo(registry: RepoRegistry): RepoContext = registry.current()
}
