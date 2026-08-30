package de.hoennig.werkator.framework

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class TestcontainersSmokeTest :
    FunSpec({

        test("Testcontainers starts a container") {
            val container =
                GenericContainer(DockerImageName.parse("alpine:3"))
                    .withCommand("sh", "-c", "sleep 30")
            container.start()
            container.isRunning shouldBe true
            container.stop()
        }
    })
