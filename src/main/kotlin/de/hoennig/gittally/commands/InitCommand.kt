package de.hoennig.gittally.commands

import de.hoennig.gittally.SecretFiles
import de.hoennig.gittally.git.GitService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
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

    @Option(
        names = ["--systemd"],
        description = ["also generate a systemd user unit that runs `gittally server` for this repository"],
    )
    var systemd: Boolean = false

    /** Replaceable for tests: the jar this JVM was started from, or null when not run via `java -jar`. */
    internal var jarPathResolver: () -> Path? = { runningJarPath() }

    /** Replaceable for tests: the `java` binary of the current JVM. */
    internal var javaExecutableResolver: () -> Path = { Paths.get(System.getProperty("java.home"), "bin", "java") }

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
        if (systemd) {
            createSystemdFiles(root, normalizedWorkingDir)
        }
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
        SecretFiles.createDirectoriesOwnerOnly(file.parent)
        val content =
            """
            # Machine- or user-specific overrides and secrets. Keys here win over .gittally.yml.
            git:
              account: "${detected.account}"              # technical username for git HTTPS authentication
              token: ""                                   # Gitea API token — never commit this
            """.trimIndent()
        // this is where the operator pastes the Gitea token, so it must never exist
        // world-readable — on a shared host that would hand out git push access
        SecretFiles.writeOwnerOnly(file, content + "\n")
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
              # HTTP port of the `server` subcommand
              port: 18080
              # bind address of the `server` subcommand
              bindAddress: 0.0.0.0
              # optional Impressum (legal disclosure) link in the web UI footer; empty hides the link
              impressumUrl: ""
              # Opt-in managed nginx+certbot Docker container for HTTPS, for hosts without
              # a usable reverse proxy (see docs/deployment.md). Off by default.
              nginx:
                enabled: false          # manage an nginx container with Let's Encrypt certificates
                serverName: ""          # public DNS name served by nginx; required when enabled
                httpPort: 8080          # host port published as nginx port 80
                httpsPort: 8443         # host port published as nginx port 443
                upstreamHost: ""        # host nginx proxies to; empty = serverName
                containerName: ""       # empty = gittally-nginx-<repo-name>
                stateDir: ""            # empty = XDG_STATE_HOME (or ~/.local/state) + /gittally/nginx/<repo-key>
                letsencryptEmail: ""    # e-mail for the Let's Encrypt account; empty registers without one

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
              # additionally drop builds older than this age (h/d suffix, e.g. 30d); empty = no age limit;
              # a branch's newest build is never age-pruned
              retentionMaxAge: ""
              # keep each branch's latest green build beyond the retention limits,
              # so the permanent /branches/<branch-key>/... artifact URLs stay valid while newer builds fail
              keepLatestGreen: true

            # Controls the branch-polling loop.
            watcher:
              # delay between poll cycles (s/m/h/d suffix)
              pollInterval: 10s
              # max commit age for new origin branches to be pulled automatically
              newBranchMaxAge: 5d
              # honor branches.<name>.requirePullRequest; set false for a plain git origin
              # without pull-request refs (refs/pull/*/head) — gated branches then build on new commits
              pullRequestGate: true

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
                # build only while the branch head matches a pull-request head on origin
                # (refs/pull/*/head — read via plain git, no API token needed)
                requirePullRequest: false
                autoBuild:
                  enabled: false        # whether to rebuild on schedule
                  times: ["01:00"]      # UTC times HH:MM for scheduled builds
                docker:
                  enabled: false        # run clean/build commands in a Docker container instead of natively
                  image: ""             # image for the build container; required when enabled
                  dockerfile: ""        # Dockerfile to (re)build the image from when missing or stale; empty pulls the image as-is
                  context: "."          # Docker build context used with dockerfile
                  network: ""           # Docker network mode for the build container; empty = Docker default
                  env: {}               # additional environment variables set inside the build container
            """.trimIndent()
        file.toFile().writeText(content + "\n")
        println("created ${file.toFile().relativeTo(normalizedWorkingDir.toFile())}")
    }

    private fun createSystemdFiles(
        root: Path,
        normalizedWorkingDir: Path,
    ) {
        val jarPath = jarPathResolver()
        if (jarPath == null) {
            println("Error: cannot determine the GitTally jar path — run `init --systemd` via `java -jar <path-to>/gittally.jar`")
            return
        }
        val gittallyDir = root.resolve(".git/gittally")
        SecretFiles.createDirectoriesOwnerOnly(gittallyDir)
        val unitName = SystemdServiceFiles.unitName(root)
        val unitFile = gittallyDir.resolve(unitName)
        val envFile = gittallyDir.resolve(SystemdServiceFiles.ENV_FILE_NAME)

        unitFile.toFile().writeText(
            SystemdServiceFiles.unitFileContent(
                repoRoot = root,
                javaExecutable = javaExecutableResolver(),
                jarPath = jarPath,
                envFile = envFile,
            ),
        )
        println("created ${unitFile.toFile().relativeTo(normalizedWorkingDir.toFile())}")

        if (envFile.toFile().exists()) {
            println("${envFile.toFile().relativeTo(normalizedWorkingDir.toFile())} already exists — not overwritten")
        } else {
            envFile.toFile().writeText(SystemdServiceFiles.envFileContent())
            println("created ${envFile.toFile().relativeTo(normalizedWorkingDir.toFile())}")
        }

        // the nightly Docker cleanup is host-global: every repository generates the same
        // units, so with several GitTally instances the symlinks simply coincide
        val pruneServiceFile = gittallyDir.resolve(SystemdServiceFiles.PRUNE_SERVICE_NAME)
        val pruneTimerFile = gittallyDir.resolve(SystemdServiceFiles.PRUNE_TIMER_NAME)
        pruneServiceFile.toFile().writeText(SystemdServiceFiles.pruneServiceContent())
        println("created ${pruneServiceFile.toFile().relativeTo(normalizedWorkingDir.toFile())}")
        pruneTimerFile.toFile().writeText(SystemdServiceFiles.pruneTimerContent())
        println("created ${pruneTimerFile.toFile().relativeTo(normalizedWorkingDir.toFile())}")

        println("install and start the service and the nightly Docker cleanup with:")
        println("  ln -sf $unitFile ~/.config/systemd/user/$unitName")
        println("  ln -sf $pruneServiceFile ~/.config/systemd/user/${SystemdServiceFiles.PRUNE_SERVICE_NAME}")
        println("  ln -sf $pruneTimerFile ~/.config/systemd/user/${SystemdServiceFiles.PRUNE_TIMER_NAME}")
        println("  systemctl --user daemon-reload")
        println("  systemctl --user enable --now $unitName")
        println("  systemctl --user enable --now ${SystemdServiceFiles.PRUNE_TIMER_NAME}")
    }

    private data class DetectedValues(
        val baseUrl: String = "",
        val owner: String = "",
        val repo: String = "",
        val account: String = "",
    )

    companion object {
        /**
         * The jar this JVM was started from. With `java -jar` the launch command starts with the
         * jar path; as a fallback (e.g. custom launchers) the Spring Boot loader's nested code
         * source URL contains it. Null when running from classes (IDE, Gradle, tests).
         */
        private fun runningJarPath(): Path? {
            val launchCommand = System.getProperty("sun.java.command").orEmpty().substringBefore(' ')
            if (launchCommand.endsWith(".jar")) {
                return Paths.get(launchCommand).toAbsolutePath().normalize()
            }
            val codeSource =
                InitCommand::class.java.protectionDomain.codeSource
                    ?.location
                    ?.toString()
                    .orEmpty()
            return Regex("""(/[^!]*?\.jar)""")
                .find(codeSource)
                ?.let { Paths.get(it.groupValues[1]) }
        }
    }
}
