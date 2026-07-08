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
     * Keep each branch's latest green (SUCCESS) build beyond [retentionPerBranch],
     * so the permanent `/branches/<branch-key>/…` artifact URLs stay valid while newer
     * builds fail; the build is still dropped once its branch is gone from origin.
     */
    val keepLatestGreen: Boolean = true,
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
    /**
     * Honor the `branches.<name>.requirePullRequest` gates. Set false for a plain git
     * origin without pull-request refs (no Gitea/GitHub) — gated branches then build
     * on new commits like any other branch. Typically overridden per machine in
     * `.git/gittally/.gittally.yml` when the committed config enables the gates.
     */
    val pullRequestGate: Boolean = true,
)

data class BranchConfig(
    val buildCommand: String = "./gradlew --console=plain --no-daemon test",
    val cleanCommand: String = "rm -rf build",
    val artifactDirs: List<String> = listOf("build/reports"),
    val stdoutLog: String = "build.stdout.log",
    val stderrLog: String = "build.stderr.log",
    /**
     * The watcher builds this branch only while its head commit matches a pull-request
     * head (`refs/pull/<n>/head` on origin); manual `build` commands are not affected.
     */
    val requirePullRequest: Boolean = false,
    val autoBuild: AutoBuildConfig = AutoBuildConfig(),
    val docker: DockerConfig = DockerConfig(),
)

data class DockerConfig(
    /** Run the clean and build commands in a Docker container instead of natively. */
    val enabled: Boolean = false,
    /** Image for the build container; required when [enabled]. */
    val image: String = "",
    /** Dockerfile to build [image] from when it is missing or stale; empty uses [image] as-is (pulled on demand). */
    val dockerfile: String = "",
    /** Docker build context used with [dockerfile]. */
    val context: String = ".",
    /** Docker network mode for the build container; empty uses Docker's default network. */
    val network: String = "",
    /** Additional environment variables set inside the build container. */
    val env: Map<String, String> = emptyMap(),
)

data class AutoBuildConfig(
    val enabled: Boolean = false,
    val times: List<String> = listOf("01:00"),
)
