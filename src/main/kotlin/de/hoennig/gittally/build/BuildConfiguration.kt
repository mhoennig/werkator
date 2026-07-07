package de.hoennig.gittally.build

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths

@Configuration
class BuildConfiguration {
    /**
     * Results file relative to the working directory, matching how `ConfigLoader`
     * resolves the `.git/gittally/` override file. Nothing is touched until the
     * first build runs, so the bean is safe outside a git repository.
     */
    @Bean
    fun buildResultRepository(): BuildResultRepository = FileBuildResultRepository(Paths.get(".git/gittally/build-results.json"))
}
