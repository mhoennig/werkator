package de.hoennig.gittally.server

import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.git.GitService
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The branches-view data, shared by the JSON API and the server-rendered page:
 * every origin branch joined with its latest build (or an `unknown` placeholder
 * when never built), ordered like the legacy branches view — main/master first,
 * then flat names, then hierarchical names, alphabetical within each group.
 * Legacy listed local branches; the new watcher's branch universe is origin.
 */
@Component
class BranchListing(
    private val gitService: GitService,
    private val repository: BuildResultRepository,
) {
    fun branches(workingDir: Path = Paths.get(".")): List<BranchDto> =
        gitService
            .originBranchHeads(workingDir)
            .entries
            .sortedWith(compareBy({ sortGroup(it.key) }, { it.key }))
            .map { (branch, headCommit) -> BranchDto.from(branch, headCommit, repository.latestFor(branch)) }

    private fun sortGroup(branch: String): Int =
        when {
            branch == "main" || branch == "master" -> 0
            '/' !in branch -> 1
            else -> 2
        }
}
