package de.hoennig.gittally.build

import de.hoennig.gittally.config.BranchConfig
import de.hoennig.gittally.config.DockerConfig
import de.hoennig.gittally.git.GitCommandResult
import de.hoennig.gittally.git.GitCommandRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DockerBuildRunnerTest : FunSpec() {
    private val commandRunner = mockk<GitCommandRunner>()
    private val socketLocator = mockk<DockerSocketLocator>()
    private lateinit var runner: DockerBuildRunner
    private lateinit var repoDir: Path
    private lateinit var workspace: Path
    private val captured = mutableListOf<List<String>>()

    private fun dockerBranchConfig(
        image: String = "build-env:latest",
        dockerfile: String = "",
        network: String = "",
        env: Map<String, String> = emptyMap(),
    ): BranchConfig =
        BranchConfig(
            docker =
                DockerConfig(
                    enabled = true,
                    image = image,
                    dockerfile = dockerfile,
                    network = network,
                    env = env,
                ),
        )

    init {
        beforeEach {
            clearMocks(commandRunner, socketLocator)
            captured.clear()
            repoDir = Files.createTempDirectory("gittally-docker-runner")
            workspace = repoDir.resolve("workspace")
            every { commandRunner.run(any(), any(), any(), any()) } returns GitCommandResult(0, "", "")
            every { commandRunner.runOrThrow(any(), any(), any(), any()) } returns GitCommandResult(0, "", "")
            every { commandRunner.runOrThrow(listOf("id", "-u"), any(), any(), any()) } returns GitCommandResult(0, "1000\n", "")
            every { commandRunner.runOrThrow(listOf("id", "-g"), any(), any(), any()) } returns GitCommandResult(0, "1001\n", "")
            every { socketLocator.locate("1000") } returns
                DockerSocket(Paths.get("/var/run/docker.sock"), rootless = false, gid = 999L)
            runner = DockerBuildRunner(commandRunner, socketLocator)
            runner.processStarter = { command, _ ->
                captured += command
                ProcessBuilder("true").start()
            }
        }

        test("assembles the exact docker run command (rootful socket, default network)") {
            val branchConfig = dockerBranchConfig(env = mapOf("FOO" to "bar"))

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, branchConfig)

            val repoKey = ArtifactKeys.repoKey(repoDir)
            val args = captured.single()
            val script = args[args.size - 5]
            args shouldBe
                listOf(
                    "docker",
                    "run",
                    "--rm",
                    "--init",
                    "--name",
                    "gittally-build-$repoKey-${ArtifactKeys.branchKey("main")}",
                    "--label",
                    "org.hoennig.gittally=true",
                    "--label",
                    "org.hoennig.gittally.repository=$repoKey",
                    "--label",
                    "org.hoennig.gittally.role=build",
                    "--workdir",
                    "$workspace",
                    "--volume",
                    "$workspace:$workspace",
                    "--volume",
                    "gittally-gradle-$repoKey:/gradle-user-home",
                    "--env",
                    "HOME=/tmp/docker-home",
                    "--env",
                    "GRADLE_USER_HOME=/gradle-user-home",
                    "--env",
                    "branch=main",
                    "--env",
                    "FOO=bar",
                    "--volume",
                    "/var/run/docker.sock:/var/run/docker.sock",
                    "--env",
                    "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock",
                    "--env",
                    "DOCKER_HOST=unix:///var/run/docker.sock",
                    "--group-add",
                    "999",
                    "--user",
                    "0",
                    "--env",
                    "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal",
                    "--add-host",
                    "host.docker.internal:host-gateway",
                    "build-env:latest",
                    "sh",
                    "-c",
                    script,
                    "sh",
                    "1000",
                    "1001",
                    "./gradlew test",
                )
            script shouldContain "bash -c \"\$3\""
            script shouldContain "chown -R \"\$1:\$2\""
            script shouldContain "exit \$build_exit"
        }

        test("a rootless socket runs the container as root (the host user) without group-add or host-id chown") {
            every { socketLocator.locate("1000") } returns
                DockerSocket(Paths.get("/run/user/1000/docker.sock"), rootless = true, gid = 998L)

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig())

            val args = captured.single()
            args shouldContain "/run/user/1000/docker.sock:/var/run/docker.sock"
            args[args.indexOf("--user") + 1] shouldBe "0"
            args shouldNotContain "--group-add"
            // container root already is the host user — the repair chown must not target the host ids,
            // which would push the worktree files into the subuid range
            args[args.size - 3] shouldBe "0"
            args[args.size - 2] shouldBe "0"
            verify {
                commandRunner.runOrThrow(
                    match { it.take(2) == listOf("docker", "run") && it.takeLast(2) == listOf("0", "0") },
                    repoDir,
                    any(),
                    any(),
                )
            }
        }

        test("exposes git metadata read-only with the gittally dir masked for a worktree workspace") {
            val gitDir = repoDir.resolve(".git")
            val adminDir = gitDir.resolve("worktrees/workspace")
            Files.createDirectories(adminDir)
            Files.createDirectories(gitDir.resolve("gittally"))
            Files.createDirectories(workspace)
            Files.writeString(workspace.resolve(".git"), "gitdir: $adminDir\n")

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig())

            val args = captured.single()
            args shouldContain "$gitDir:$gitDir:ro"
            args[args.indexOf("--tmpfs") + 1] shouldBe "${gitDir.resolve("gittally")}"
            args shouldContain "$adminDir:$adminDir"
        }

        test("mounts no git metadata when the workspace is not a worktree") {
            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig())

            val args = captured.single()
            args shouldNotContain "--tmpfs"
            args.none { it.endsWith(":ro") } shouldBe true
        }

        test("host network keeps Testcontainers on localhost without add-host") {
            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig(network = "host"))

            val args = captured.single()
            args[args.indexOf("--network") + 1] shouldBe "host"
            args shouldContain "TESTCONTAINERS_HOST_OVERRIDE=localhost"
            args shouldNotContain "--add-host"
        }

        test("without a docker socket the container runs without socket mount as root") {
            every { socketLocator.locate("1000") } returns null

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig())

            val args = captured.single()
            args shouldNotContain "/var/run/docker.sock:/var/run/docker.sock"
            args shouldNotContain "DOCKER_HOST=unix:///var/run/docker.sock"
            args[args.indexOf("--user") + 1] shouldBe "0"
        }

        test("builds a missing image from the Dockerfile with the input labels") {
            Files.writeString(repoDir.resolve("Dockerfile"), "FROM eclipse-temurin:21\n")
            every { commandRunner.run(match { it.take(3) == listOf("docker", "image", "inspect") }, any(), any(), any()) } returns
                GitCommandResult(1, "", "no such image")

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig(dockerfile = "Dockerfile"))

            val dockerfileHash = DockerImageInputs.dockerfileSha256(repoDir.resolve("Dockerfile"))
            val inputsHash = DockerImageInputs.inputsSha256(dockerfileHash, "Dockerfile", ".")
            verify {
                commandRunner.runOrThrow(
                    listOf(
                        "docker",
                        "build",
                        "--label",
                        "org.gittally.dockerfile=Dockerfile",
                        "--label",
                        "org.gittally.dockerfile-sha256=$dockerfileHash",
                        "--label",
                        "org.gittally.build-context=.",
                        "--label",
                        "org.gittally.build-inputs-sha256=$inputsHash",
                        "-t",
                        "build-env:latest",
                        "-f",
                        "Dockerfile",
                        ".",
                    ),
                    repoDir,
                    any(),
                    any(),
                )
            }
        }

        test("skips the image build when the build-inputs label still matches") {
            Files.writeString(repoDir.resolve("Dockerfile"), "FROM eclipse-temurin:21\n")
            val dockerfileHash = DockerImageInputs.dockerfileSha256(repoDir.resolve("Dockerfile"))
            val inputsHash = DockerImageInputs.inputsSha256(dockerfileHash, "Dockerfile", ".")
            every { commandRunner.run(match { it.take(3) == listOf("docker", "image", "inspect") }, any(), any(), any()) } returns
                GitCommandResult(0, "$inputsHash\n", "")

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig(dockerfile = "Dockerfile"))

            verify(exactly = 0) {
                commandRunner.runOrThrow(match { it.take(2) == listOf("docker", "build") }, any(), any(), any())
            }
        }

        test("prepares the gradle cache volume once but removes the container before every command") {
            val branchConfig = dockerBranchConfig()

            runner.start("./gradlew clean", workspace, mapOf("branch" to "main"), repoDir, branchConfig)
            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, branchConfig)

            val repoKey = ArtifactKeys.repoKey(repoDir)
            verify(exactly = 1) {
                commandRunner.runOrThrow(listOf("docker", "volume", "create", "gittally-gradle-$repoKey"), repoDir, any(), any())
            }
            verify(exactly = 2) {
                commandRunner.run(
                    listOf("docker", "rm", "-f", "gittally-build-$repoKey-${ArtifactKeys.branchKey("main")}"),
                    repoDir,
                    any(),
                    any(),
                )
            }
        }

        test("removes stale labelled build containers once, before the first docker build") {
            every { commandRunner.run(match { it.take(3) == listOf("docker", "ps", "-aq") }, any(), any(), any()) } returns
                GitCommandResult(0, "abc\ndef\n", "")

            runner.start("./gradlew clean", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig())
            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, dockerBranchConfig())

            verify(exactly = 1) { commandRunner.run(match { it.take(3) == listOf("docker", "ps", "-aq") }, any(), any(), any()) }
            verify { commandRunner.run(listOf("docker", "rm", "-f", "abc", "def"), repoDir, any(), any()) }
        }

        test("fails without a configured image") {
            val branchConfig = BranchConfig(docker = DockerConfig(enabled = true))

            val exception =
                shouldThrow<IllegalArgumentException> {
                    runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, branchConfig)
                }

            exception.message shouldContain "docker.image"
        }
    }
}
