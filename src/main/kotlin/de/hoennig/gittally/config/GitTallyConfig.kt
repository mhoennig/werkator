package de.hoennig.gittally.config

data class GitTallyConfig(
    val server: ServerConfig = ServerConfig(),
    val git: GitConfig = GitConfig(),
    val gitea: GiteaConfig = GiteaConfig(),
    val artifacts: ArtifactsConfig = ArtifactsConfig(),
    val watcher: WatcherConfig = WatcherConfig(),
    val branches: Map<String, BranchConfig> = mapOf("default" to BranchConfig()),
)

data class ServerConfig(
    val publicBaseUrl: String = "",
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

data class ArtifactsConfig(
    val retentionPerBranch: Int = 3,
)

data class WatcherConfig(
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
