package de.hoennig.werkator.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files

class DockerImageInputsTest : FunSpec() {
    init {
        test("dockerfileSha256 is a stable hex checksum of the file contents") {
            val dir = Files.createTempDirectory("werkator-docker-inputs")
            val dockerfile = dir.resolve("Dockerfile")
            Files.writeString(dockerfile, "FROM eclipse-temurin:21\n")

            val hash = DockerImageInputs.dockerfileSha256(dockerfile)

            hash shouldMatch Regex("[0-9a-f]{64}")
            DockerImageInputs.dockerfileSha256(dockerfile) shouldBe hash
        }

        test("inputs checksum changes when the Dockerfile contents change") {
            val dir = Files.createTempDirectory("werkator-docker-inputs")
            val dockerfile = dir.resolve("Dockerfile")
            Files.writeString(dockerfile, "FROM eclipse-temurin:21\n")
            val before =
                DockerImageInputs.inputsSha256(
                    DockerImageInputs.dockerfileSha256(dockerfile),
                    "Dockerfile",
                    ".",
                )

            Files.writeString(dockerfile, "FROM eclipse-temurin:22\n")

            DockerImageInputs.inputsSha256(
                DockerImageInputs.dockerfileSha256(dockerfile),
                "Dockerfile",
                ".",
            ) shouldNotBe before
        }

        test("inputs checksum changes when the Dockerfile path or the context change") {
            val dockerfileHash = "0".repeat(64)
            val base = DockerImageInputs.inputsSha256(dockerfileHash, "ci/Dockerfile", "ci")

            DockerImageInputs.inputsSha256(dockerfileHash, "other/Dockerfile", "ci") shouldNotBe base
            DockerImageInputs.inputsSha256(dockerfileHash, "ci/Dockerfile", "other") shouldNotBe base
            DockerImageInputs.inputsSha256(dockerfileHash, "ci/Dockerfile", "ci") shouldBe base
        }
    }
}
