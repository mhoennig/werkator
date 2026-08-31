package de.hoennig.werkator.build

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths

@Configuration
class BuildConfiguration {
    /**
     * Results file relative to the working directory, matching how `ConfigLoader`
     * resolves the `.git/werkator/` override file. Nothing is touched until the
     * first build runs, so the bean is safe outside a git repository.
     */
    @Bean
    fun buildResultRepository(): BuildResultRepository = FileBuildResultRepository(Paths.get(".git/werkator/build-results.json"))
}
