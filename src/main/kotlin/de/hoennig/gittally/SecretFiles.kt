package de.hoennig.werkator

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Creation of files and directories that hold secrets — the Gitea token in
 * `.git/werkator/.werkator.yml` and the control token.
 *
 * The permissions are set *at creation*, never with a `chmod` after the write:
 * writing at the umask default first (typically `0644`) would leave a window in
 * which the secret is world-readable, which matters on multi-tenant hosts.
 * On non-POSIX filesystems the permissions are silently skipped.
 */
object SecretFiles {
    private val OWNER_ONLY_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private val OWNER_ONLY_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

    /** Writes [content] as a `0600` file, replacing an existing file. */
    fun writeOwnerOnly(
        file: Path,
        content: String,
    ) {
        val bytes = content.toByteArray()
        Files.deleteIfExists(file)
        try {
            Files
                .newByteChannel(file, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), OWNER_ONLY_FILE)
                .use { it.write(ByteBuffer.wrap(bytes)) }
        } catch (_: UnsupportedOperationException) {
            Files.write(file, bytes)
        }
    }

    /** Creates [directory] and its parents; a directory created here gets mode `0700`. */
    fun createDirectoriesOwnerOnly(directory: Path) {
        val missing = generateSequence(directory) { it.parent }.takeWhile { !Files.exists(it) }.toList().asReversed()
        missing.forEach { path ->
            try {
                Files.createDirectory(path, OWNER_ONLY_DIRECTORY)
            } catch (_: UnsupportedOperationException) {
                Files.createDirectory(path)
            }
        }
    }
}
