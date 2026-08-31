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

    private fun rootfsUnpacked(rootfs: String = "/srv/buildenv.tar.zst"): Path =
        repoDir
            .resolve(BwrapBuildRunner.BUILDENV_DIR)
            .resolve(rootfs.sha12())
            .resolve(BwrapBuildRunner.ROOTFS_DIR)

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

        test("unpacks the rootfs on demand and assembles the exact bwrap command") {
            every {
                commandRunner.runOrThrow(
                    listOf("tar", "--no-same-owner", "-xf", "/srv/buildenv.tar.zst", "-C", rootfsUnpacked().toString()),
                    repoDir,
                    any(),
                    any(),
                )
            } returns
                GitCommandResult(0, "", "")

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            val args = captured.single()
            val rootfsDir = args[args.indexOf("--ro-bind") + 1]
            args shouldBe
                listOf(
                    "bwrap",
                    "--unshare-user",
                    "--unshare-pid",
                    "--die-with-parent",
                    "--uid",
                    "0",
                    "--gid",
                    "0",
                    "--ro-bind",
                    rootfsUnpacked().toString(),
                    "/",
                    "--bind",
                    workspace.toString(),
                    workspace.toString(),
                    "--bind",
                    repoDir.resolve(".git/werkator/buildenv/home").toString(),
                    "/root",
                    "--ro-bind",
                    "/etc/resolv.conf",
                    "/etc/resolv.conf",
                    "--proc",
                    "/proc",
                    "--dev",
                    "/dev",
                    "--tmpfs",
                    "/tmp",
                    "--setenv",
                    "HOME",
                    "/root",
                    "--setenv",
                    "branch",
                    "main",
                    "--chdir",
                    workspace.toString(),
                    "/bin/sh",
                    "-c",
                    "./gradlew test",
                )
            Files.isDirectory(rootfsUnpacked()) shouldBe true
        }

        test("binds a relative workspace path at its absolute location") {
            // bwrap creates mountpoints for bind destinations inside the sandbox;
            // a relative path would land in the read-only rootfs and fail with
            // "Can't mkdir parents ...: Read-only file system" (seen on the webspace).
            every {
                commandRunner.runOrThrow(
                    listOf("tar", "--no-same-owner", "-xf", "/srv/buildenv.tar.zst", "-C", rootfsUnpacked().toString()),
                    repoDir,
                    any(),
                    any(),
                )
            } returns
                GitCommandResult(0, "", "")

            val relativeWorkspace = repoDir.relativize(workspace)

            runner.start("./gradlew test", relativeWorkspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            val args = captured.single()
            val absolute = workspace.toAbsolutePath().normalize().toString()
            args[args.indexOf("--bind") + 1] shouldBe absolute
            args[args.indexOf("--bind") + 2] shouldBe absolute
            args[args.indexOf("--chdir") + 1] shouldBe absolute
        }

        test("does not re-unpack an already prepared rootfs") {
            Files.createDirectories(rootfsUnpacked())

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            verify(exactly = 0) { commandRunner.runOrThrow(match { it.first() == "tar" }, any(), any(), any()) }
        }

        test("adds bwrap env and passes the branch environment through") {
            Files.createDirectories(rootfsUnpacked())

            runner.start(
                "./gradlew test",
                workspace,
                mapOf("branch" to "main"),
                repoDir,
                bwrapBranchConfig(env = mapOf("FOO" to "bar")),
            )

            val args = captured.single()
            args[args.indexOf("branch") - 1] shouldBe "--setenv"
            args[args.indexOf("branch") + 1] shouldBe "main"
            args[args.indexOf("FOO") - 1] shouldBe "--setenv"
            args[args.indexOf("FOO") + 1] shouldBe "bar"
        }

        test("exposes git metadata read-only with the werkator dir masked for a worktree workspace") {
            val gitDir = repoDir.resolve(".git")
            val adminDir = gitDir.resolve("worktrees/workspace")
            Files.createDirectories(adminDir)
            Files.createDirectories(gitDir.resolve("werkator"))
            Files.createDirectories(workspace)
            Files.writeString(workspace.resolve(".git"), "gitdir: $adminDir\n")
            Files.createDirectories(rootfsUnpacked())

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            val args = captured.single()
            args[args.indexOf(gitDir.toString()) - 1] shouldBe "--ro-bind"
            args[args.indexOf("$gitDir/werkator") - 1] shouldBe "--tmpfs"
            args[args.indexOf(adminDir.toString()) - 1] shouldBe "--bind"
        }

        test("mounts no git metadata when the workspace is not a worktree") {
            Files.createDirectories(rootfsUnpacked())
            Files.createDirectories(workspace)

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig())

            val args = captured.single()
            val gitDir = repoDir.resolve(".git")
            // the sandbox's own /tmp tmpfs is always present; the point is that no tmpfs
            // masks .git/werkator and no worktree admin dir is bound
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

        test("downloads a URL rootfs once before unpacking") {
            val url = "https://example.test/buildenv.tar.zst"
            val downloadTarget =
                repoDir
                    .resolve(BwrapBuildRunner.BUILDENV_DIR)
                    .resolve(url.sha12())
                    .resolve("buildenv.tar.zst")
            every { commandRunner.runOrThrow(listOf("curl", "-fsSL", "-o", downloadTarget.toString(), url), repoDir, any(), any()) } returns
                GitCommandResult(0, "", "")
            every { commandRunner.runOrThrow(match { it.first() == "tar" }, any(), any(), any()) } returns
                GitCommandResult(0, "", "")

            runner.start("./gradlew test", workspace, mapOf("branch" to "main"), repoDir, bwrapBranchConfig(rootfs = url))

            verify {
                commandRunner.runOrThrow(listOf("curl", "-fsSL", "-o", downloadTarget.toString(), url), repoDir, any(), any())
            }
            verify { commandRunner.runOrThrow(match { it.first() == "tar" }, repoDir, any(), any()) }
        }
    }

    private fun String.sha12(): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
