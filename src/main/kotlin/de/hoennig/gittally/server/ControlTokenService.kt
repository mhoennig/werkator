package de.hoennig.gittally.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Guards the mutating build endpoints with a shared secret, like the legacy cancel
 * token. The token is generated once and persisted (mode 600) so operators — and
 * the step-08 UI, server-side — can read it; delete the file to rotate it.
 */
class ControlTokenService(
    private val tokenFile: Path,
) {
    @Synchronized
    fun token(): String {
        if (Files.isRegularFile(tokenFile)) {
            Files
                .readAllLines(tokenFile)
                .firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val token = generateToken()
        Files.createDirectories(tokenFile.parent)
        Files.writeString(tokenFile, token + "\n")
        restrictToOwner(tokenFile)
        return token
    }

    /** Constant-time comparison; null or blank never matches. */
    fun matches(submittedToken: String?): Boolean =
        !submittedToken.isNullOrBlank() &&
            MessageDigest.isEqual(submittedToken.toByteArray(), token().toByteArray())

    /** 24 random bytes as hex, like legacy `openssl rand -hex 24`. */
    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun restrictToOwner(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // non-POSIX filesystem; the file stays with default permissions
        }
    }
}
