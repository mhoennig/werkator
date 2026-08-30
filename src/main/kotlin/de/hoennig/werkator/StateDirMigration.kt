package de.hoennig.werkator

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Moves the state directory of an installation that predates the rename to Werkator,
 * once, at the first start after the update.
 *
 * The configuration is found under either name ([de.hoennig.werkator.config.ConfigFiles]),
 * but the state is not: build history, the control token, the auto-build slots and the
 * build worktrees live at one fixed path. A missing state directory is as quiet as a
 * missing configuration — the instance would come up with an empty history and a fresh
 * control token, and nothing would fail. So this is done rather than documented.
 */
object StateDirMigration {
    const val DIR = ".git/werkator"

    private const val LEGACY_DIR = ".git/gittally"
    private const val WORKTREES = "worktrees"

    private val log = LoggerFactory.getLogger(StateDirMigration::class.java)

    /**
     * Renames `.git/gittally` to `.git/werkator` in [workingDir], if the first exists and
     * the second does not. Never throws: a failed move must not stop a CI, it must say
     * what to do by hand.
     */
    fun migrateIfNeeded(workingDir: Path) {
        val legacy = workingDir.resolve(LEGACY_DIR)
        val current = workingDir.resolve(DIR)
        if (!Files.isDirectory(legacy)) return
        if (Files.exists(current)) {
            // both exist: which of the two is the live state is not ours to guess
            log.warn("{} exists next to {} — the leftover is ignored, remove it once you are sure", LEGACY_DIR, DIR)
            return
        }
        try {
            Files.move(legacy, current)
        } catch (e: IOException) {
            log.error("could not move {} to {}: {} — move it by hand", LEGACY_DIR, DIR, e.message)
            return
        }
        log.info("moved {} to {}: build history, control token and configuration kept", LEGACY_DIR, DIR)
        dropWorktrees(current)
        warnAboutUnits(current)
    }

    /**
     * The moved worktrees point at their old path in both directions, so they are dropped
     * rather than repaired: the next build of a branch creates its worktree again, and
     * `GitWorktreeWorkspaces` prunes the stale admin entry before it does.
     */
    private fun dropWorktrees(stateDir: Path) {
        val worktrees = stateDir.resolve(WORKTREES)
        if (!Files.isDirectory(worktrees)) return
        if (worktrees.toFile().deleteRecursively()) {
            log.info("dropped the moved build worktrees; each is recreated by its branch's next build")
        } else {
            log.warn("could not drop the moved build worktrees in {} — delete them by hand", worktrees)
        }
    }

    /**
     * The generated systemd unit lives in the state directory and is symlinked from
     * `~/.config/systemd/user`, so the move leaves that link dangling — the service keeps
     * running and fails to start the next time.
     */
    private fun warnAboutUnits(stateDir: Path) {
        val units =
            Files
                .list(stateDir)
                .use { paths -> paths.map { it.fileName.toString() }.filter { it.endsWith(".service") }.toList() }
        if (units.isEmpty()) return
        log.warn(
            "the systemd unit {} moved with the state directory and its symlink now dangles — " +
                "re-run `werkator init --systemd` and re-link it",
            units.joinToString(", "),
        )
    }
}
