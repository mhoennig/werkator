package de.hoennig.werkator.server

import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import org.springframework.stereotype.Component

/**
 * The branches-view data, shared by the JSON API and the server-rendered page:
 * every origin branch joined with its latest build (or an `unknown` placeholder
 * when never built), plus one extra row per named auto-build slot that has recorded
 * builds (e.g. `master@nightly`, sorted right after its branch). Ordered like the
 * legacy branches view — main/master first, then flat names, then hierarchical
 * names, alphabetical within each group.
 * Legacy listed local branches; the new watcher's branch universe is origin.
 */
@Component
class BranchListing(
    private val gitService: GitService,
) {
    fun branches(repo: RepoContext): List<BranchDto> {
        val repository = repo.results
        val heads = gitService.originBranchHeads(repo.workingDir)
        val namedResults = repository.latestPerName().filter { it.name != it.branch && it.branch in heads }
        val branchesWithNamedPool = namedResults.map { it.branch }.toSet()
        val branchRows =
            heads
                .mapNotNull { (branch, headCommit) ->
                    // latestFor groups by build name, so a named slot's results never shadow the branch row
                    val latest = repository.latestFor(branch)
                    // A branch whose builds all belong to named definitions has no default pool,
                    // and an empty row for it would read as "never built" next to its real ones.
                    // Without any build at all the row stays: that a branch is known and idle is
                    // exactly what it says then.
                    if (latest == null && branch in branchesWithNamedPool) {
                        null
                    } else {
                        BranchDto.from(branch, headCommit, latest)
                    }
                }
        val namedRows =
            namedResults.map { latest -> BranchDto.from(latest.branch, latest.commit, latest, name = latest.name) }
        return (branchRows + namedRows)
            .sortedWith(compareBy({ sortGroup(it.branch) }, { it.branch }, { it.name }))
            .map { row ->
                // the permanent link belongs to the build it resolves to, not to every build of the name
                val isLatestGreen =
                    row.artifactKey.isNotEmpty() && row.artifactKey == repository.latestGreenFor(row.name)?.artifactKey
                if (isLatestGreen) row.copy(latestGreenUrl = BranchPermalinks.permanentUrl(row.name)) else row
            }
    }

    private fun sortGroup(branch: String): Int =
        when {
            branch == "main" || branch == "master" -> 0
            '/' !in branch -> 1
            else -> 2
        }
}
