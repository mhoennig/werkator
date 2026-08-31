package de.hoennig.werkator.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ServerNginxLifecycleTest : FunSpec() {
    private val manager = mockk<NginxProxyManager>(relaxUnitFun = true)
    private val scheduler = mockk<ScheduledExecutorService>(relaxed = true)
    private lateinit var lifecycle: ServerNginxLifecycle
    private var schedulerCreated = 0

    init {
        beforeEach {
            clearMocks(manager, scheduler)
            schedulerCreated = 0
            lifecycle = ServerNginxLifecycle(manager)
            lifecycle.schedulerFactory = {
                schedulerCreated++
                scheduler
            }
        }

        test("nothing is scheduled and no container is touched when disabled") {
            every { manager.isEnabled() } returns false

            lifecycle.onApplicationReady()

            schedulerCreated shouldBe 0
            verify(exactly = 0) { manager.start() }
        }

        test("starts nginx on the scheduler thread and schedules the daily renewal check") {
            every { manager.isEnabled() } returns true
            every { scheduler.execute(any()) } answers { firstArg<Runnable>().run() }
            val renewalTask = slot<Runnable>()
            every { scheduler.scheduleWithFixedDelay(capture(renewalTask), 24L, 24L, TimeUnit.HOURS) } returns mockk()

            lifecycle.onApplicationReady()

            verify(exactly = 1) { manager.start() }
            renewalTask.captured.run()
            verify(exactly = 1) { manager.renewCertificateAndReload() }
        }

        test("shutdown stops the scheduler and removes the container") {
            every { manager.isEnabled() } returns true
            lifecycle.onApplicationReady()

            lifecycle.onShutdown()

            verify(exactly = 1) { scheduler.shutdownNow() }
            verify(exactly = 1) { manager.stop() }
        }

        test("shutdown without a prior start still asks the manager to stop") {
            lifecycle.onShutdown()

            verify(exactly = 1) { manager.stop() }
            verify(exactly = 0) { scheduler.shutdownNow() }
        }
    }
}
