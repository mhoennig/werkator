package de.hoennig.werkator.build

import de.hoennig.werkator.git.GitService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Provides an isolated build workspace per branch so multiple branches can build
 * concurrently without touching the primary checkout or each other.
 */
fun interface BranchWorkspaces {
    /** A ready-to-build workspace for [branch] with [commit] checked out. */
    fun prepare(
        branch: String,
        commit: String,
        repoDir: Path,
    ): Path
}

/**
 * One reusable git worktree per branch under `.git/werkator/worktrees/<branchKey>`,
 * checked out detached at the requested commit. Reuse keeps incremental build
 * caches; the branch's `cleanCommand` decides how much of them survives.
 */
@Component
class GitWorktreeWorkspaces(
    private val gitService: GitService,
) : BranchWorkspaces {
    private val log = LoggerFactory.getLogger(GitWorktreeWorkspaces::class.java)

    override fun prepare(
        branch: String,
        commit: String,
        repoDir: Path,
    ): Path {
        val workspace = repoDir.resolve(WORKTREES_DIR).resolve(ArtifactKeys.branchKey(branch))
        if (Files.exists(workspace.resolve(".git"))) {
            gitService.checkoutDetached(commit, workspace)
        } else {
            gitService.worktreePrune(repoDir)
            if (Files.exists(workspace)) {
                log.warn("removing broken workspace of branch {}: {}", branch, workspace)
                workspace.toFile().deleteRecursively()
            }
            gitService.worktreeAdd(workspace, commit, repoDir)
        }
        return workspace
    }

    companion object {
        const val WORKTREES_DIR = ".git/werkator/worktrees"
    }
}
