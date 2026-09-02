package de.hoennig.werkator.commands

import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoRegistry
import picocli.CommandLine.Option

/**
 * The `--repo` selector of the repository-scoped commands (ADR 0009): names an entry
 * of the instance registry. Without it a command means the current working directory
 * when that is served, otherwise the first registered repository — so inside a
 * repository every command behaves exactly as it did with one.
 */
class RepoOption {
    @Option(
        names = ["--repo"],
        paramLabel = "<name>",
        description = ["registered repository to act on (default: the current directory)"],
    )
    var name: String? = null

    fun select(registry: RepoRegistry): RepoContext {
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: return registry.current()
        return registry.byName(wanted)
            ?: throw IllegalArgumentException(
                "no repository named '$wanted' is registered (registered: ${registry.all().joinToString(", ") { it.name }})",
            )
    }
}
