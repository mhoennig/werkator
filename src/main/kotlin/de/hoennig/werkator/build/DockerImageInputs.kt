package de.hoennig.werkator.build

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Staleness checksum of the Docker image build inputs, compatible with legacy
 * `docker_build_inputs_sha256`: the SHA-256 of the Dockerfile contents combined
 * with the configured Dockerfile and context paths, stored as an image label.
 */
object DockerImageInputs {
    const val INPUTS_LABEL = "org.werkator.build-inputs-sha256"

    fun dockerfileSha256(dockerfile: Path): String = sha256Hex(Files.readAllBytes(dockerfile))

    fun inputsSha256(
        dockerfileHash: String,
        dockerfile: String,
        context: String,
    ): String = sha256Hex("$dockerfileHash\n$dockerfile\n$context\n".toByteArray())

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
