package de.hoennig.gittally.server

import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.git.GitService
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths

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
    private val repository: BuildResultRepository,
) {
    fun branches(workingDir: Path = Paths.get(".")): List<BranchDto> {
        val heads = gitService.originBranchHeads(workingDir)
        val branchRows =
            heads.map { (branch, headCommit) ->
                // latestFor groups by build name, so a named slot's results never shadow the branch row
                BranchDto.from(branch, headCommit, repository.latestFor(branch))
            }
        val namedRows =
            repository
                .latestPerName()
                .filter { it.name != it.branch && it.branch in heads }
                .map { latest -> BranchDto.from(latest.branch, latest.commit, latest, name = latest.name) }
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
