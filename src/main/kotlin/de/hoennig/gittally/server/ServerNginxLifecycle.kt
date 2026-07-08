package de.hoennig.gittally.server

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Starts the managed nginx container once the web server is up and removes it on
 * shutdown, plus a daily certificate-renewal check — an improvement over legacy,
 * which renewed only at process start and relied on frequent self-update restarts.
 * Only in the `server` profile, like [ServerWatcherLifecycle]; with
 * `server.nginx.enabled: false` (the default) nothing is scheduled or touched.
 * Startup runs on the scheduler thread, so a slow certificate issuance never
 * blocks the web server; the single thread also serializes startup and renewals.
 */
@Component
@Profile("server")
class ServerNginxLifecycle(
    private val nginxProxyManager: NginxProxyManager,
) {
    /** Replaceable for tests: the scheduler running startup and renewal checks. */
    internal var schedulerFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gittally-nginx").apply { isDaemon = true }
        }
    }

    private var scheduler: ScheduledExecutorService? = null

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!nginxProxyManager.isEnabled()) {
            return
        }
        scheduler =
            schedulerFactory().also {
                it.execute(nginxProxyManager::start)
                it.scheduleWithFixedDelay(
                    nginxProxyManager::renewCertificateAndReload,
                    RENEWAL_CHECK_INTERVAL_HOURS,
                    RENEWAL_CHECK_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                )
            }
    }

    @PreDestroy
    fun onShutdown() {
        scheduler?.shutdownNow()
        scheduler = null
        nginxProxyManager.stop()
    }

    companion object {
        /** Daily, like certbot's own systemd timer; `certbot renew` only acts when a certificate is due. */
        const val RENEWAL_CHECK_INTERVAL_HOURS = 24L
    }
}
