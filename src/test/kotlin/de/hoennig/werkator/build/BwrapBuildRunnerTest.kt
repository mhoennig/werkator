package de.hoennig.werkator.build

import de.hoennig.werkator.config.BranchConfig
import de.hoennig.werkator.config.BwrapConfig
import de.hoennig.werkator.git.GitCommandResult
import de.hoennig.werkator.git.GitCommandRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path

class BwrapBuildRunnerTest : FunSpec() {
    private val commandRunner = mockk<GitCommandRunner>()
    private lateinit var runner: BwrapBuildRunner
    private lateinit var repoDir: Path
    private lateinit var workspace: Path
    private val captured = mutableListOf<List<String>>()

    private fun bwrapBranchConfig(
        rootfs: String = "/srv/buildenv.tar.zst",
        env: Map<String, String> = emptyMap(),
    ): BranchConfig =
        BranchConfig(
            bwrap =
                BwrapConfig(
                    enabled = true,
                    rootfs = rootfs,
                    env = env,
                ),
        )

    private fun imageName(rootfs: String = "/srv/buildenv.tar.zst"): String = "werkator-buildenv-${rootfs.sha12()}"

    /** The image is already loaded: `werkdock images` lists it, so no load runs. */
    private fun givenImageLoaded(rootfs: String = "/srv/buildenv.tar.zst") {
        every { commandRunner.runOrThrow(listOf("werkdock", "images"), repoDir, any(), any()) } returns
            GitCommandResult(0, imageName(rootfs) + "\n", "")
    }

    private fun givenImageMissing() {
        every { commandRunner.runOrThrow(listOf("werkdock", "images"), repoDir, any(), any()) } returns
            GitCommandResult(0, "some-other-image\n", "")
    }

    init {
        beforeEach {
            clearMocks(commandRunner)
            captured.clear()
            repoDir = Files.createTempDirectory("werkator-bwrap-runner")
            workspace = repoDir.resolve("workspace")
            runner = BwrapBuildRunner(commandRunner)
            runner.processStarter = { command, _ ->
                captured += command
                ProcessBuilder("true").start()
            }
        }

        test("assembles the exact werkdock run command for a loaded image") {
            givenImageLoaded()

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            captured.single() shouldBe
                listOf(
                    "werkdock",
                    "run",
                    "--rm",
                    "-v",
                    "$repoDir:$repoDir",
                    "-v",
                    "$workspace:$workspace",
                    "-v",
                    "${repoDir.resolve(".git/werkator/buildenv/home")}:/root",
                    "-e",
                    "branch=main",
                    "-w",
                    workspace.toString(),
                    imageName(),
                    "/bin/sh",
                    "-c",
                    "./gradlew test",
                )
        }

        test("loads the image once when werkdock does not know it yet") {
            givenImageMissing()
            every {
                commandRunner.runOrThrow(
                    listOf("werkdock", "load", "-i", "/srv/buildenv.tar.zst", "--name", imageName()),
                    repoDir,
                    any(),
                    any(),
                )
            } returns GitCommandResult(0, "", "")

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            verify {
                commandRunner.runOrThrow(
                    listOf("werkdock", "load", "-i", "/srv/buildenv.tar.zst", "--name", imageName()),
                    repoDir,
                    any(),
                    any(),
                )
            }
        }

        test("does not load an image werkdock already has") {
            givenImageLoaded()

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            verify(exactly = 0) { commandRunner.runOrThrow(match { "load" in it }, any(), any(), any()) }
        }

        test("uses the configured werkdock binary path") {
            every { commandRunner.runOrThrow(listOf("/opt/bin/werkdock", "images"), repoDir, any(), any()) } returns
                GitCommandResult(0, imageName() + "\n", "")
            val branchConfig =
                BranchConfig(
                    bwrap = BwrapConfig(enabled = true, rootfs = "/srv/buildenv.tar.zst", werkdock = "/opt/bin/werkdock"),
                )

            runner.start("./gradlew test", workspace, emptyMap(), repoDir, branchConfig)

            captured.single().first() shouldBe "/opt/bin/werkdock"
        }

        test("mounts a relative workspace path at its absolute location") {
            givenImageLoaded()
            val relativeWorkspace = repoDir.relativize(workspace)

            runner.start("./gradlew test", relativeWorkspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            val args = captured.single()
            val absolute = workspace.toAbsolutePath().normalize().toString()
            args shouldContainElement "-v"
            args[args.indexOf("-w") + 1] shouldBe absolute
            args.count { it == "$absolute:$absolute" } shouldBe 1
        }

        test("adds bwrap env after the branch environment") {
            givenImageLoaded()

            runner.start(
                "./gradlew test",
                workspace,
                mapOf("branch" to "main"),
                repoDir,
                bwrapBranchConfig(env = mapOf("FOO" to "bar")),
            )

            val args = captured.single()
            args[args.indexOf("branch=main") - 1] shouldBe "-e"
            args[args.indexOf("FOO=bar") - 1] shouldBe "-e"
            args.indexOf("branch=main") shouldBe args.indexOf("FOO=bar") - 2
        }

        test("exposes git metadata read-only with the werkator dir masked, in mount order") {
            val gitDir = repoDir.resolve(".git")
            val adminDir = gitDir.resolve("worktrees/workspace")
            Files.createDirectories(adminDir)
            Files.createDirectories(gitDir.resolve("werkator"))
            Files.createDirectories(workspace)
            Files.writeString(workspace.resolve(".git"), "gitdir: $adminDir\n")
            givenImageLoaded()

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            val args = captured.single()
            args[args.indexOf("$gitDir:$gitDir:ro") - 1] shouldBe "-v"
            args[args.indexOf("$gitDir/werkator") - 1] shouldBe "--tmpfs"
            args[args.indexOf("$adminDir:$adminDir") - 1] shouldBe "-v"
            // order: ro .git, tmpfs mask, admin dir, then the workspace bind that
            // shadows the mask at its own path
            val roGit = args.indexOf("$gitDir:$gitDir:ro")
            val mask = args.indexOf("$gitDir/werkator")
            val admin = args.indexOf("$adminDir:$adminDir")
            val workspaceBind = args.indexOf("$workspace:$workspace")
            (roGit < mask && mask < admin && admin < workspaceBind) shouldBe true
        }

        test("mounts no git metadata when the workspace is not a worktree") {
            givenImageLoaded()
            Files.createDirectories(workspace)

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            val args = captured.single()
            val gitDir = repoDir.resolve(".git")
            args.none { it == "$gitDir/werkator" } shouldBe true
            args.none { it.contains("worktrees/") } shouldBe true
        }

        test("fails without a configured rootfs") {
            val branchConfig = BranchConfig(bwrap = BwrapConfig(enabled = true))

            val exception =
                shouldThrow<IllegalArgumentException> {
                    runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, branchConfig)
                }

            exception.message shouldContain "bwrap.rootfs"
        }

        test("downloads a URL rootfs once before loading it") {
            val url = "https://example.test/buildenv.tar.zst"
            val downloadTarget =
                repoDir
                    .resolve(BwrapBuildRunner.BUILDENV_DIR)
                    .resolve(url.sha12())
                    .resolve("buildenv.tar.zst")
            givenImageMissing()
            every { commandRunner.runOrThrow(listOf("curl", "-fsSL", "-o", downloadTarget.toString(), url), repoDir, any(), any()) } returns
                GitCommandResult(0, "", "")
            every { commandRunner.runOrThrow(match { "load" in it }, any(), any(), any()) } returns
                GitCommandResult(0, "", "")

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig(rootfs = url))

            verify {
                commandRunner.runOrThrow(listOf("curl", "-fsSL", "-o", downloadTarget.toString(), url), repoDir, any(), any())
            }
            verify {
                commandRunner.runOrThrow(
                    listOf("werkdock", "load", "-i", downloadTarget.toString(), "--name", "werkator-buildenv-${url.sha12()}"),
                    repoDir,
                    any(),
                    any(),
                )
            }
        }
    }

    private infix fun List<String>.shouldContainElement(element: String) = (element in this) shouldBe true

    private fun String.sha12(): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
