package de.hoennig.werkator.build

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * Legacy-compatible artifact key naming: sanitized name plus a 12-char SHA-256 prefix,
 * so keys are filesystem- and URL-safe but still unique for branch names that
 * sanitize to the same string.
 */
object ArtifactKeys {
    fun branchKey(branch: String): String = "${sanitize(branch)}-${sha256Prefix(branch)}"

    /**
     * The hash-free key of the permanent `/branches/<key>/…` artifact URLs, e.g.
     * `feature/demo` → `feature_demo`. Unlike [branchKey] it is not necessarily
     * unique; lookups must reject ambiguous matches.
     */
    fun permanentBranchKey(branch: String): String = sanitize(branch)

    fun buildKey(
        branch: String,
        startedAt: Instant,
    ): String = "${branchKey(branch)}-${sanitize(startedAt.toString())}-${sha256Prefix("$branch\t$startedAt")}"

    /** Legacy `repository_key`: the sanitized absolute repository path. */
    fun repoKey(repoDir: Path): String = sanitize(repoDir.toAbsolutePath().normalize().toString())

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256Prefix(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
