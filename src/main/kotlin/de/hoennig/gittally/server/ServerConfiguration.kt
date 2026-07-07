package de.hoennig.gittally.server

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths

@Configuration
class ServerConfiguration {
    /**
     * Token file relative to the working directory, matching how `ConfigLoader`
     * and the `BuildResultRepository` bean resolve their files. Nothing is written
     * until the first guarded request, so the bean is safe outside a git repository.
     */
    @Bean
    fun controlTokenService(): ControlTokenService = ControlTokenService(Paths.get(".git/gittally/control-token"))
}
