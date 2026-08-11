package de.hoennig.gittally.server

import de.hoennig.gittally.SecretFiles
import java.nio.file.Files
import java.nio.file.Path
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
        SecretFiles.createDirectoriesOwnerOnly(tokenFile.parent)
        SecretFiles.writeOwnerOnly(tokenFile, token + "\n")
        return token
    }

    /**
     * Constant-time comparison; null or blank never matches. Both sides are hashed first
     * so the comparison always runs over two 32-byte buffers and cannot return early on a
     * length mismatch — which would leak the token length.
     */
    fun matches(submittedToken: String?): Boolean =
        !submittedToken.isNullOrBlank() &&
            MessageDigest.isEqual(sha256(submittedToken), sha256(token()))

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    /** 24 random bytes as hex, like legacy `openssl rand -hex 24`. */
    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
