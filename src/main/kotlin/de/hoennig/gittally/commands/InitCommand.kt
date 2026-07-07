package de.hoennig.gittally.commands

import de.hoennig.gittally.git.GitService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.nio.file.Path
import java.nio.file.Paths

@Component
@Command(
    name = "init",
    description = ["Initialize GitTally for the current repository"],
    mixinStandardHelpOptions = true,
)
class InitCommand(
    private val gitService: GitService,
) : Runnable {
    var workingDir: Path = Paths.get(".")

    override fun run() {
        val normalizedWorkingDir = workingDir.toAbsolutePath().normalize()
        val root =
            try {
                gitService.getTopLevel(normalizedWorkingDir)
            } catch (e: Exception) {
                println("Error: ${e.message}")
                return
            }

        val originUrl = gitService.getOriginUrl(root)
        val detected = detectFromUrl(originUrl)

        createRepoInstallConfig(root, detected, normalizedWorkingDir)
        createProjectConfig(root, detected, normalizedWorkingDir)
    }

    private fun detectFromUrl(url: String?): DetectedValues {
        if (url == null) return DetectedValues()

        if (url.startsWith("http")) {
            val regex = Regex("""https?://(?:([^@]+)@)?([^/]+)/([^/]+)/([^/.]+)(?:\.git)?""")
            val match = regex.find(url)
            if (match != null) {
                val (user, host, owner, repo) = match.destructured
                return DetectedValues(
                    baseUrl = "https://$host",
                    owner = owner,
                    repo = repo,
                    account = user,
                )
            }
        } else if (url.contains("@") && url.contains(":")) {
            // Assume SSH: git@host:owner/repo.git
            val regex = Regex("""([^@]+)@([^:]+):([^/]+)/([^/.]+)(?:\.git)?""")
            val match = regex.find(url)
            if (match != null) {
                val (_, host, owner, repo) = match.destructured
                return DetectedValues(
                    baseUrl = "https://$host",
                    owner = owner,
                    repo = repo,
                    account = "", // SSH user 'git' is not the account name we want for HTTPS
                )
            }
        }

        return DetectedValues()
    }

    private fun createRepoInstallConfig(
        root: Path,
        detected: DetectedValues,
        normalizedWorkingDir: Path,
    ) {
        val file = root.resolve(".git/gittally/.gittally.yml")
        if (file.toFile().exists()) {
            println("${file.toFile().relativeTo(normalizedWorkingDir.toFile())} already exists — not overwritten")
            return
        }
        file.parent.toFile().mkdirs()
        val content =
            """
            # Machine- or user-specific overrides and secrets. Keys here win over .gittally.yml.
            git:
              account: "${detected.account}"              # technical username for git HTTPS authentication
              token: ""                                   # Gitea API token — never commit this
            """.trimIndent()
        file.toFile().writeText(content + "\n")
        println("created ${file.toFile().relativeTo(normalizedWorkingDir.toFile())}")
    }

    private fun createProjectConfig(
        root: Path,
        detected: DetectedValues,
        normalizedWorkingDir: Path,
    ) {
        val file = root.resolve(".gittally.yml")
        if (file.toFile().exists()) {
            println("${file.toFile().relativeTo(normalizedWorkingDir.toFile())} already exists — not overwritten")
            return
        }
        val content =
            """
            server:
              # Public base URL of this GitTally installation — used for all links posted to Gitea.
              publicBaseUrl: ""

            # Gitea integration for fetching commits and posting build statuses.
            gitea:
              baseUrl: ${detected.baseUrl}   # base URL of the Gitea instance
              owner: ${detected.owner}                      # repository owner (user or organisation) for Gitea API (e.g. status checks)
              repo: ${detected.repo}                      # repository name
              statusContext: GitTally            # label shown on Gitea commit status checks (default: GitTally)

            # Build execution.
            builds:
              # how many branches may build at the same time (at most one build per branch regardless)
              maxConcurrent: 1

            # Build artifact storage and retention.
            artifacts:
              # root directory for stored artifacts; empty = XDG_STATE_HOME (or ~/.local/state) + /gittally/artifacts/<repo-key>
              rootDir: ""
              # number of builds to keep per branch
              retentionPerBranch: 3

            # Controls the branch-polling loop.
            watcher:
              # max commit age for new origin branches to be pulled automatically
              newBranchMaxAge: 5d

            # Per-branch build configuration.
            # Use "default" as the fallback for all branches not listed explicitly.
            branches:
              default:
                # run before each build
                cleanCommand: rm -rf build
                # shell command for each build 
                buildCommand: ./gradlew --console=plain --no-daemon test
                # directories copied as build artifacts
                artifactDirs:                                         
                  - build/reports
                stdoutLog: build.stdout.log   # filename for captured stdout
                stderrLog: build.stderr.log   # filename for captured stderr
                autoBuild:
                  enabled: false        # whether to rebuild on schedule
                  times: ["01:00"]      # UTC times HH:MM for scheduled builds
            """.trimIndent()
        file.toFile().writeText(content + "\n")
        println("created ${file.toFile().relativeTo(normalizedWorkingDir.toFile())}")
    }

    private data class DetectedValues(
        val baseUrl: String = "",
        val owner: String = "",
        val repo: String = "",
        val account: String = "",
    )
}
