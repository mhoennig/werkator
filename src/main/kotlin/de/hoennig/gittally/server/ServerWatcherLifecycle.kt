package de.hoennig.gittally.server

import de.hoennig.gittally.watcher.Watcher
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Starts the watcher poll loop once the server context is ready and stops it on
 * shutdown. Only in the `server` profile — CLI commands and tests never start
 * the loop (see [Watcher]).
 */
@Component
@Profile("server")
class ServerWatcherLifecycle(
    private val watcher: Watcher,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        watcher.start()
    }

    @PreDestroy
    fun onShutdown() {
        watcher.stop()
    }
}
