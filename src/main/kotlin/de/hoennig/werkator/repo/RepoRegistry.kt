package de.hoennig.werkator.repo

import de.hoennig.werkator.config.ConfigException
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.config.RepositoryEntry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The repositories this instance serves (ADR 0009): one [RepoContext] per entry of the
 * home configuration's `repositories`, or — without a home config or with an empty
 * registry — the current working directory, exactly as before.
 *
 * Opened once, on first use, and loudly: an entry that is no git repository or a name
 * used twice aborts the start with a message naming the home file, because an instance
 * serving the wrong set is worse than one that does not come up. A repository whose
 * configuration Werkator must not read (version or format violation) is the exception:
 * it is skipped with an error, like a branch config violation fails only that branch —
 * the other repositories keep building.
 */
@Component
class RepoRegistry(
    private val configLoader: ConfigLoader,
    private val repoContexts: RepoContexts,
) {
    private val log = LoggerFactory.getLogger(RepoRegistry::class.java)

    private val contexts: List<RepoContext> by lazy { open() }

    /** Every served repository, in registry order. */
    fun all(): List<RepoContext> = contexts

    /** The repository registered under [name], or null. */
    fun byName(name: String): RepoContext? = contexts.firstOrNull { it.name == name }

    /**
     * The repository a command without a selector means: the current working directory
     * when it is served (so `werkator status` inside a repository behaves as today),
     * otherwise the first registered one.
     */
    fun current(): RepoContext {
        val cwd = Paths.get(".").toAbsolutePath().normalize()
        return contexts.firstOrNull { it.workingDir.toAbsolutePath().normalize() == cwd } ?: contexts.first()
    }

    private fun open(): List<RepoContext> {
        val entries = configLoader.loadInstance()?.repositories.orEmpty()
        if (entries.isEmpty()) {
            return listOf(repoContexts.open(Paths.get(".")))
        }
        val home = configLoader.instanceFile()
        val opened = entries.mapNotNull { openEntry(it, home) }
        val duplicates = opened.groupBy { it.name }.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            val listed =
                duplicates.entries.joinToString(
                    "; ",
                ) { (name, repos) -> "$name: ${repos.joinToString(", ") { it.workingDir.toString() }}" }
            throw IllegalStateException("$home registers the same repository name more than once ($listed); set a distinct name per entry")
        }
        check(opened.isNotEmpty()) { "$home registers no readable repository" }
        return opened
    }

    private fun openEntry(
        entry: RepositoryEntry,
        home: Path,
    ): RepoContext? {
        val dir = resolve(entry.path)
        if (!Files.isDirectory(dir) || !Files.exists(dir.resolve(".git"))) {
            throw IllegalStateException(
                "$home registers ${entry.path.ifBlank { "an entry without a path" }}, which is not a git repository ($dir)",
            )
        }
        val name = entry.name.trim().ifEmpty { RepoContexts.defaultName(dir) }
        try {
            // the configuration is read here only to find out whether Werkator may read it at all
            configLoader.load(dir)
        } catch (e: ConfigException) {
            log.error("not serving repository {} ({}): {}", name, dir, e.message)
            return null
        }
        return repoContexts.open(dir, name)
    }

    /** `~` expands to the home directory; a relative path is relative to the home directory, not the cwd. */
    private fun resolve(path: String): Path {
        val home = configLoader.homeDir
        val expanded =
            when {
                path == "~" -> home
                path.startsWith("~/") -> home.resolve(path.removePrefix("~/"))
                else -> home.resolve(path)
            }
        return expanded.toAbsolutePath().normalize()
    }
}
