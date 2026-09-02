package de.hoennig.werkator.server

import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.watcher.Watcher
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Starts the watcher poll loop over the served repository once the server context
 * is ready and stops it on shutdown. Only in the `server` profile — CLI commands
 * and tests never start the loop (see [Watcher]).
 */
@Component
@Profile("server")
class ServerWatcherLifecycle(
    private val watcher: Watcher,
    private val repo: RepoContext,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        watcher.start(repo)
    }

    @PreDestroy
    fun onShutdown() {
        watcher.stop()
    }
}
