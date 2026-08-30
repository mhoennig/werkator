package de.hoennig.werkator.commands

/**
 * Port of the legacy `resolve_branch_name` partial-name matching: a branch-name
 * fragment resolves against the local and origin branch names. An exact name always
 * wins; otherwise a unique substring match resolves, and multiple matches are
 * reported back (the legacy script prompted interactively instead — a one-shot
 * CLI command cannot).
 */
object BranchNameResolution {
    sealed interface Match

    data class Resolved(
        val branch: String,
    ) : Match

    data class Ambiguous(
        val candidates: List<String>,
    ) : Match

    data object NoMatch : Match

    fun resolve(
        fragment: String,
        candidates: List<String>,
    ): Match {
        if (fragment in candidates) {
            return Resolved(fragment)
        }
        val matches = candidates.filter { fragment in it }
        return when (matches.size) {
            0 -> NoMatch
            1 -> Resolved(matches.single())
            else -> Ambiguous(matches)
        }
    }
}
