package de.hoennig.werkator.framework

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Probes that Testcontainers can actually start a container on this host.
 * Skipped, not failed, when no Docker is present — that is what lets Werkator
 * build itself on a Docker-less host (e.g. a Hostsharing webspace, where its own
 * build runs in the bubblewrap sandbox); see `tools/werkator-build-prerequisites.sh`.
 */
class TestcontainersSmokeTest :
    FunSpec({

        test("Testcontainers starts a container")
            .config(enabledIf = { dockerAvailable() }) {
                val container =
                    GenericContainer(DockerImageName.parse("alpine:3"))
                        .withCommand("sh", "-c", "sleep 30")
                container.start()
                container.isRunning shouldBe true
                container.stop()
            }
    }) {
    companion object {
        private fun dockerAvailable(): Boolean =
            try {
                DockerClientFactory.instance().isDockerAvailable()
            } catch (_: Throwable) {
                false
            }
    }
}
