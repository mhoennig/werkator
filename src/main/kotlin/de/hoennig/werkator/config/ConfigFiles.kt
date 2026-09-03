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

    /**
     * The instance configuration (ADR 0009), relative to the home directory of the user
     * running Werkator. Deliberately the same file name: only the location carries the
     * meaning — home is the instance, the repository root is the project, `.git` is the
     * machine.
     */
    const val INSTANCE = COMMITTED

    /**
     * The applied instance fragment (`init --apply`, step 23): a config-schema YAML
     * fragment installed verbatim as its own layer — above the committed project
     * config, below the hand-edited machine config. Kept separate so applying never
     * rewrites the machine config (its comments and secrets stay untouched) and
     * re-applying is a plain file replacement, never a merge that can duplicate.
     */
    const val APPLIED = ".git/werkator/.werkator.applied.yml"

    /** The name the committed configuration had before the rename. */
    const val LEGACY_COMMITTED = ".gittally.yml"

    private const val LEGACY_REPO_INSTALL = ".git/gittally/$LEGACY_COMMITTED"

    /**
     * The state directory moves without touching the file inside it, so between the
     * move and the rename the machine configuration sits under the old name in the
     * new directory. `StateDirMigration` closes that gap where it moves the directory
     * itself; this candidate covers a directory somebody moved by hand.
     */
    private const val MOVED_REPO_INSTALL = ".git/werkator/$LEGACY_COMMITTED"

    /** Both names of the committed configuration, current first. */
    val committed = listOf(COMMITTED, LEGACY_COMMITTED)

    /** Every path the machine-specific configuration can sit at, current first. */
    val repoInstall = listOf(REPO_INSTALL, MOVED_REPO_INSTALL, LEGACY_REPO_INSTALL)

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
