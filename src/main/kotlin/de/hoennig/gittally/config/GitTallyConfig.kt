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
    /**
     * Public base URL of this installation; empty defaults to `https://<nginx.serverName>/`
     * when [NginxConfig.serverName] is set (applied by [ConfigLoader]).
     */
    val publicBaseUrl: String = "",
    /** HTTP port of the `server` subcommand; 18080 like the legacy artifact server. */
    val port: Int = 18080,
    /**
     * Loopback by default: the UI and the API are unauthenticated, so reaching them should
     * require the host's reverse proxy. Set `0.0.0.0` explicitly to expose all interfaces —
     * which the managed nginx container needs (see `docs/deployment.md`).
     */
    val bindAddress: String = "127.0.0.1",
    /** Optional Impressum (legal disclosure) link shown in the web UI footer; empty hides the link. */
    val impressumUrl: String = "",
    val nginx: NginxConfig = NginxConfig(),
)

/**
 * Opt-in managed nginx+certbot Docker container serving GitTally over HTTPS,
 * for hosts without a usable reverse proxy (ADR 0005). Off by default; the
 * reverse-proxy deployment from `docs/deployment.md` stays the recommended setup.
 */
data class NginxConfig(
    /** Manage an nginx Docker container with Let's Encrypt certificates. */
    val enabled: Boolean = false,
    /** Public DNS name served by nginx and used for the certificate; required when [enabled]. */
    val serverName: String = "",
    /** Host port published as nginx port 80 (ACME challenge + HTTPS redirect). */
    val httpPort: Int = 8080,
    /** Host port published as nginx port 443. */
    val httpsPort: Int = 8443,
    /** Host nginx proxies to; empty uses [serverName] (the container cannot reach `localhost`). */
    val upstreamHost: String = "",
    /** Name of the managed container; empty means `gittally-nginx-<repo-name>`. */
    val containerName: String = "",
    /**
     * Directory for nginx config, certificates, and logs; empty means the platform
     * default `XDG_STATE_HOME` (or `~/.local/state`) + `/gittally/nginx/<repo-key>`.
     */
    val stateDir: String = "",
    /** E-mail for the Let's Encrypt account; empty registers without one. */
    val letsencryptEmail: String = "",
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
     * Additionally drop builds older than this age (e.g. `30d` or `12h`); empty means no
     * age limit. Combines with [retentionPerBranch] — a build is kept only while it
     * satisfies both limits. A branch's newest build is never age-pruned, so dormant
     * branches keep their last status.
     */
    val retentionMaxAge: String = "",
    /**
     * Keep each branch's latest green (SUCCESS) build beyond [retentionPerBranch] and
     * [retentionMaxAge], so the permanent `/branches/<branch-key>/…` artifact URLs stay
     * valid while newer builds fail; the build is still dropped once its branch is gone
     * from origin.
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
