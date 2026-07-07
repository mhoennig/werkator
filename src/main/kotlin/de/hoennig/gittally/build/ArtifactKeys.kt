package de.hoennig.gittally.build

import java.security.MessageDigest
import java.time.Instant

/**
 * Legacy-compatible artifact key naming: sanitized name plus a 12-char SHA-256 prefix,
 * so keys are filesystem- and URL-safe but still unique for branch names that
 * sanitize to the same string.
 */
object ArtifactKeys {
    fun branchKey(branch: String): String = "${sanitize(branch)}-${sha256Prefix(branch)}"

    fun buildKey(
        branch: String,
        startedAt: Instant,
    ): String = "${branchKey(branch)}-${sanitize(startedAt.toString())}-${sha256Prefix("$branch\t$startedAt")}"

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256Prefix(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
