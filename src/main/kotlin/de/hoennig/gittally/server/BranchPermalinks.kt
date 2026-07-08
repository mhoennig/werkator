package de.hoennig.gittally.server

import de.hoennig.gittally.build.ArtifactKeys
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Resolves the permanent `/branches/<branch-key>/…` artifact URLs: the key is the
 * hash-free [ArtifactKeys.permanentBranchKey] (the full [ArtifactKeys.branchKey]
 * works too), and the target is the branch's latest green build. Resolution happens
 * per request, so a permanent URL follows every new green build and stays valid as
 * long as the branch exists on origin and has ever built successfully — green only,
 * a permanent link never points at a failed build's artifacts.
 */
@Component
class BranchPermalinks(
    private val repository: BuildResultRepository,
) {
    fun latestGreenBuild(branchKey: String): BuildResult {
        val branches =
            repository
                .latestPerBranch()
                .map { it.branch }
                .filter { branchKey == ArtifactKeys.permanentBranchKey(it) || branchKey == ArtifactKeys.branchKey(it) }
        val branch =
            when (branches.size) {
                0 -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "no recorded builds for branch key '$branchKey'")
                1 -> branches.single()
                else -> throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "branch key '$branchKey' is ambiguous (${branches.joinToString()}); use the full branch key with hash suffix",
                )
            }
        return repository.latestGreenFor(branch)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "branch '$branch' has no successful build")
    }

    companion object {
        /** The permanent artifact-index URL of [branch], shown in the branches view. */
        fun permanentUrl(branch: String): String = "/branches/${ArtifactKeys.permanentBranchKey(branch)}"
    }
}
