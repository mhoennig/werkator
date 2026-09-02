package de.hoennig.werkator.config

/**
 * The instance configuration (ADR 0009): `~/.werkator.yml` in the home directory of the
 * user running Werkator — one instance per OS user. It owns what is shared by every
 * repository the instance serves: the repository registry, the `server` section, the
 * global `executor.maxConcurrent`, and the watcher poll interval. Everything else in
 * the file is either `defaults` — a fragment in the repository config schema merged
 * *below* every repository's own layers — or ignored.
 *
 * Without this file, Werkator serves the current working directory exactly as before.
 * With it, the registry wins over the current directory (`werkator server` serves the
 * registered repositories wherever it is started), and the instance-level keys of a
 * repository's own files are ignored with a warning naming both files, never merged.
 */
data class InstanceConfig(
    /** What this file declares about the Werkator that reads it; see [VersionRequirement]. */
    val werkator: WerkatorMeta = WerkatorMeta(),
    val server: ServerConfig = ServerConfig(),
    val executor: ExecutorConfig = ExecutorConfig(),
    val watcher: InstanceWatcherConfig = InstanceWatcherConfig(),
    /** The registry: the repositories this instance serves; empty means the current directory. */
    val repositories: List<RepositoryEntry> = emptyList(),
    /**
     * Repository-level keys in the repository config schema (e.g. one `git.account`/`git.token`
     * for every repository of the same forge), merged below each repository's own layers.
     * Raw on purpose: it is a fragment, not a configuration, and binds through the same
     * path as every other layer.
     */
    val defaults: Map<String, Any?> = emptyMap(),
)

/** The watcher settings that are the instance's, not a repository's: one loop, one delay. */
data class InstanceWatcherConfig(
    /** Delay between poll cycles over all repositories, e.g. `10s` or `1m`. */
    val pollInterval: String = "10s",
)

/** One registry entry: a repository directory and the name it is known by. */
data class RepositoryEntry(
    /** The repository's primary checkout, absolute or relative to the home directory; `~` expands. */
    val path: String = "",
    /** Short unique name for display and routes; empty means the directory basename. */
    val name: String = "",
)
