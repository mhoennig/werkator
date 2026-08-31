package de.hoennig.werkator.git

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Credential bridge for git HTTPS authentication via `GIT_ASKPASS`.
 *
 * The script itself contains no secrets; credentials are passed through
 * process environment variables so they never touch the filesystem.
 */
object GitAskPass {
    val SCRIPT: String =
        """
        #!/bin/sh
        case "${'$'}1" in
            *[Uu]sername*)
                printf '%s\n' "${'$'}WERKATOR_GIT_ACCOUNT"
                ;;
            *)
                printf '%s\n' "${'$'}WERKATOR_GIT_TOKEN"
                ;;
        esac
        """.trimIndent() + "\n"

    fun <T> withAskPass(
        account: String,
        token: String,
        block: (environment: Map<String, String>) -> T,
    ): T {
        val script =
            Files.createTempFile(
                "werkator-askpass",
                ".sh",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        try {
            Files.writeString(script, SCRIPT)
            return block(
                mapOf(
                    "GIT_ASKPASS" to script.toAbsolutePath().toString(),
                    "GIT_TERMINAL_PROMPT" to "0",
                    "WERKATOR_GIT_ACCOUNT" to account,
                    "WERKATOR_GIT_TOKEN" to token,
                ),
            )
        } finally {
            Files.deleteIfExists(script)
        }
    }
}
