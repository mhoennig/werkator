package de.hoennig.gittally.config

data class GitTallyConfig(
    val server: ServerConfig = ServerConfig(),
    val git: GitConfig = GitConfig(),
    val gitea: GiteaConfig = GiteaConfig(),
    val builds: BuildsConfig = BuildsConfig(),
    val artifacts: ArtifactsConfig = ArtifactsConfig(),
    val watcher: WatcherConfig = WatcherConfig(),
    val branches: Map<String, BranchConfig> = mapOf("default" to BranchConfig()),
)

data class ServerConfig(
    val publicBaseUrl: String = "",
    /** HTTP port of the `server` subcommand; 18080 like the legacy artifact server. */
    val port: Int = 18080,
    val bindAddress: String = "0.0.0.0",
    /** Optional Impressum (legal disclosure) link shown in the web UI footer; empty hides the link. */
    val impressumUrl: String = "",
)

data class GitConfig(
    val account: String = "",
    val token: String = "",
)

data class GiteaConfig(
    val baseUrl: String = "",
    val owner: String = "",
    val repo: String = "",
    val statusContext: String = "GitTally",
)

data class BuildsConfig(
    /** How many branches may build at the same time; at most one build per branch regardless. */
    val maxConcurrent: Int = 1,
)

data class ArtifactsConfig(
    val retentionPerBranch: Int = 3,
    /**
     * Root directory for stored build artifacts; empty means the platform default
     * `XDG_STATE_HOME` (or `~/.local/state`) + `/gittally/artifacts/<repo-key>`.
     */
    val rootDir: String = "",
)

data class WatcherConfig(
    /** Delay between poll cycles, e.g. `10s` or `1m`. */
    val pollInterval: String = "10s",
    val newBranchMaxAge: String = "5d",
)

data class BranchConfig(
    val buildCommand: String = "./gradlew --console=plain --no-daemon test",
    val cleanCommand: String = "rm -rf build",
    val artifactDirs: List<String> = listOf("build/reports"),
    val stdoutLog: String = "build.stdout.log",
    val stderrLog: String = "build.stderr.log",
    val autoBuild: AutoBuildConfig = AutoBuildConfig(),
)

data class AutoBuildConfig(
    val enabled: Boolean = false,
    val times: List<String> = listOf("01:00"),
)
