package de.hoennig.werkator.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * The names a configuration file is looked up under, current name first and the name
 * from before the rename to Werkator second — spelled exactly as it was.
 *
 * The fallback exists because a missing configuration is not an error: it leaves every
 * setting at its default. An installation that updates without moving its files would
 * therefore not fail, it would come up as a plausible-looking instance that has
 * forgotten its credentials, its addresses, and what it builds.
 */
object ConfigFiles {
    /** The committed configuration, at the repository root and in a build worktree. */
    const val COMMITTED = ".werkator.yml"

    /** The machine-specific configuration inside `.git`; secrets live here. */
    const val REPO_INSTALL = ".git/werkator/$COMMITTED"

    private const val LEGACY_COMMITTED = ".gittally.yml"
    private const val LEGACY_REPO_INSTALL = ".git/gittally/$LEGACY_COMMITTED"

    /** Both names of the committed configuration, current first. */
    val committed = listOf(COMMITTED, LEGACY_COMMITTED)

    /** Both paths of the machine-specific configuration, current first. */
    val repoInstall = listOf(REPO_INSTALL, LEGACY_REPO_INSTALL)

    /**
     * The first of [candidates] that exists under [dir], or the current name when none
     * does — so a message about a file names the one to write, never the one that is
     * history.
     */
    fun firstExisting(
        dir: Path,
        candidates: List<String> = committed,
    ): String = candidates.firstOrNull { Files.isRegularFile(dir.resolve(it)) } ?: candidates.first()

    /**
     * The committed configuration as [read] answers it for a name, current name first.
     * Null when neither name is committed — used where the file is read out of git
     * rather than off the filesystem.
     */
    fun readCommitted(read: (String) -> String?): String? = committed.firstNotNullOfOrNull(read)
}
