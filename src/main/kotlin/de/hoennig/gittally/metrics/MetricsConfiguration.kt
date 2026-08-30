package de.hoennig.werkator.metrics

import de.hoennig.werkator.build.ArtifactStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class MetricsConfiguration {
    /**
     * The aggregation state lives in the artifact root (like the legacy
     * `system_state.dat`), resolved lazily because the root comes from the config.
     * Nothing is sampled or written until [SystemMetricsCollector.start], which only
     * `ServerMetricsLifecycle` calls — the bean is inert in CLI runs and tests.
     */
    @Bean
    fun systemMetricsCollector(
        artifactStore: ArtifactStore,
        clock: Clock,
    ): SystemMetricsCollector =
        SystemMetricsCollector(
            stateFile = { artifactStore.rootDir().resolve(SystemMetricsCollector.STATE_FILE_NAME) },
            clock = clock,
        )
}
