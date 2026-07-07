package de.hoennig.gittally.server

import de.hoennig.gittally.metrics.SystemMetricsCollector
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Starts the system-metrics sampling loop once the server context is ready and
 * stops it on shutdown. Only in the `server` profile, like [ServerWatcherLifecycle].
 */
@Component
@Profile("server")
class ServerMetricsLifecycle(
    private val collector: SystemMetricsCollector,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        collector.start()
    }

    @PreDestroy
    fun onShutdown() {
        collector.stop()
    }
}
