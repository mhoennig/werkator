package de.hoennig.werkator.server

import de.hoennig.werkator.build.ArtifactKeys
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.repo.RepoContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Resolves the permanent `/branches/<branch-key>/…` artifact URLs: the key is the
 * hash-free [ArtifactKeys.permanentBranchKey] (the full [ArtifactKeys.branchKey]
 * works too) of a build name — a branch, or a named auto-build slot like
 * `master@nightly` — and the target is that name's latest green build. Resolution
 * happens per request, so a permanent URL follows every new green build and stays
 * valid as long as the branch exists on origin and the name has ever built
 * successfully — green only, a permanent link never points at a failed build's
 * artifacts.
 */
@Component
class BranchPermalinks {
    fun latestGreenBuild(
        repo: RepoContext,
        branchKey: String,
    ): BuildResult {
        val repository = repo.results
        val names =
            repository
                .latestPerName()
                .map { it.name }
                .filter { branchKey == ArtifactKeys.permanentBranchKey(it) || branchKey == ArtifactKeys.branchKey(it) }
        val name =
            when (names.size) {
                0 -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "no recorded builds for branch key '$branchKey'")
                1 -> names.single()
                else -> throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "branch key '$branchKey' is ambiguous (${names.joinToString()}); use the full branch key with hash suffix",
                )
            }
        return repository.latestGreenFor(name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "branch '$name' has no successful build")
    }

    companion object {
        /**
         * The permanent artifact-index URL of the build name (branch or named slot), shown
         * in the branches view. [base] is the repository prefix (`/repos/<name>`, empty with
         * one served repository): the key is a hash of the name alone, so two repositories
         * both having `main` would otherwise share one permanent URL — and it would resolve
         * against whichever repository the instance happens to serve unscoped.
         */
        fun permanentUrl(
            name: String,
            base: String = "",
        ): String = "$base/branches/${ArtifactKeys.permanentBranchKey(name)}"
    }
}
