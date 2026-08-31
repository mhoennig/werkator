package de.hoennig.werkator.build

import de.hoennig.werkator.config.BranchConfig
import de.hoennig.werkator.config.BwrapConfig
import de.hoennig.werkator.git.GitCommandRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Runs build commands inside a bubblewrap user-namespace sandbox (Step 17 / ADR 0007),
 * for hosts without root and without a Docker daemon (e.g. Hostsharing managed
 * webspaces). Shells out to the `bwrap` CLI via the generic [GitCommandRunner] process
 * wrapper — no library, consistent with git and docker.
 *
 * The prepared rootfs (a Debian-base archive built elsewhere, since `debootstrap` is not
 * available on the target) is unpacked on demand into `.git/werkator/buildenv/<envKey>/rootfs`,
 * shared across all branch worktrees like the Docker gradle cache volume; `<envKey>` derives
 * from a hash of the archive source, so a changed source unpacks a fresh rootfs and stale
 * ones can be pruned. The returned [Process] is the attached `bwrap` process, so log
 * streaming and cancellation work exactly like native builds (`--die-with-parent` plus
 * `--unshare-pid` tear down the whole tree on cancel). Git works inside the sandbox with
 * the same layered mounts as the Docker runner: the primary `.git` read-only with
 * `.git/werkator/` masked, see [gitMetadataMounts].
 */
@Component
class BwrapBuildRunner(
    private val commandRunner: GitCommandRunner,
) : BuildRunner {
    private val log = LoggerFactory.getLogger(BwrapBuildRunner::class.java)

    /** Replaceable process launcher so unit tests can capture the assembled `bwrap` argv. */
    internal var processStarter: (List<String>, Path) -> Process = { command, dir ->
        ProcessBuilder(command).directory(dir.toFile()).start()
    }

    override fun start(
        command: String,
        workingDir: Path,
        environment: Map<String, String>,
        repoDir: Path,
        branchConfig: BranchConfig,
        onAuxProcess: (Process) -> Unit,
    ): Process {
        val bwrap = branchConfig.bwrap
        require(bwrap.rootfs.isNotBlank()) { "branches.<name>.bwrap.rootfs must be set when bwrap.enabled is true" }
        val buildEnvRoot = buildEnvRoot(repoDir)
        val envKey = envKey(bwrap.rootfs)
        val rootfsDir = buildEnvRoot.resolve(envKey).resolve(ROOTFS_DIR)
        ensureRootfs(bwrap, rootfsDir, repoDir, onAuxProcess)
        val homeDir = buildEnvRoot.resolve(HOME_DIR)
        Files.createDirectories(homeDir)
        return processStarter(
            invocation(command, workingDir, environment, repoDir, bwrap, rootfsDir, homeDir),
            repoDir,
        )
    }

    /**
     * Unpacks the configured archive into [rootfsDir] once per environment version
     * (identified by [envKey]). Missing means "not yet unpacked"; the environment is a
     * cache like the Docker image and the Gradle volume, and stale ones are pruned with
     * the rest of `.git/werkator`.
     */
    private fun ensureRootfs(
        bwrap: BwrapConfig,
        rootfsDir: Path,
        repoDir: Path,
        onAuxProcess: (Process) -> Unit,
    ) {
        if (Files.isDirectory(rootfsDir)) {
            return
        }
        Files.createDirectories(rootfsDir)
        val archive = localArchive(bwrap.rootfs, rootfsDir.parent, repoDir, onAuxProcess)
        log.info("unpacking build environment {} into {}", bwrap.rootfs, rootfsDir)
        commandRunner.runOrThrow(
            listOf("tar", "--no-same-owner", "-xf", archive, "-C", rootfsDir.toString()),
            repoDir,
            onProcess = onAuxProcess,
        )
    }

    /**
     * Resolves [BwrapConfig.rootfs] to a local archive path: a bare or `file:` path is
     * used as-is; an `http(s)` URL is downloaded once into the buildenv root. GNU tar
     * auto-detects the compression from the archive magic, so a `.tar.gz` or `.tar.zst`
     * needs no extra flag.
     */
    private fun localArchive(
        rootfs: String,
        envDir: Path,
        repoDir: Path,
        onAuxProcess: (Process) -> Unit,
    ): String {
        if (!rootfs.startsWith("http://") && !rootfs.startsWith("https://")) {
            return rootfs.removePrefix("file://")
        }
        val fileName = rootfs.substringAfterLast('/').ifBlank { "buildenv" }
        val target = envDir.resolve(fileName)
        if (!Files.exists(target)) {
            log.info("downloading build environment {} from {}", fileName, rootfs)
            commandRunner.runOrThrow(
                listOf("curl", "-fsSL", "-o", target.toString(), rootfs),
                repoDir,
                onProcess = onAuxProcess,
            )
        }
        return target.toString()
    }

    private fun invocation(
        command: String,
        workspace: Path,
        environment: Map<String, String>,
        repoDir: Path,
        bwrap: BwrapConfig,
        rootfsDir: Path,
        homeDir: Path,
    ): List<String> {
        val args =
            mutableListOf(
                "bwrap",
                "--unshare-user",
                "--unshare-pid",
                "--die-with-parent",
                "--uid",
                "0",
                "--gid",
                "0",
                "--ro-bind",
                rootfsDir.toString(),
                "/",
            )
        args += listOf("--bind", "$workspace", "$workspace")
        args += listOf("--bind", "$homeDir", "/root")
        args += listOf("--ro-bind", "/etc/resolv.conf", "/etc/resolv.conf")
        args += gitMetadataMounts(workspace, repoDir)
        args += listOf("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp")
        args += listOf("--setenv", "HOME", "/root")
        for ((key, value) in environment) {
            args += listOf("--setenv", key, value)
        }
        for ((key, value) in bwrap.env) {
            args += listOf("--setenv", key, value)
        }
        args += listOf("--chdir", "$workspace", "/bin/sh", "-c", command)
        return args
    }

    /**
     * Makes git work inside the sandbox without exposing Werkator's secrets — the same
     * three layered mounts as the Docker runner, expressed in `bwrap` flags (bwrap nests
     * mounts by target path like Docker): the primary `.git` read-only, an empty tmpfs
     * masking `.git/werkator/` (machine config with `git.token`, control token, build
     * state), and this worktree's admin directory read-write so index-refreshing commands
     * keep working. Object and ref writes stay blocked by the read-only `.git` mount.
     * No mounts are added when the workspace is not a worktree of [repoDir].
     */
    private fun gitMetadataMounts(
        workspace: Path,
        repoDir: Path,
    ): List<String> {
        val gitDir = repoDir.toAbsolutePath().normalize().resolve(".git")
        val workspaceGitFile = workspace.resolve(".git")
        if (!Files.isDirectory(gitDir) || !Files.isRegularFile(workspaceGitFile)) {
            return emptyList()
        }
        val adminDir =
            Files
                .readString(workspaceGitFile)
                .substringAfter("gitdir:", "")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { workspace.resolve(it).normalize() }
                ?: return emptyList()
        if (!adminDir.startsWith(gitDir) || !Files.isDirectory(adminDir)) {
            return emptyList()
        }
        val args = mutableListOf("--ro-bind", "$gitDir", "$gitDir")
        val werkatorDir = gitDir.resolve("werkator")
        if (Files.isDirectory(werkatorDir)) {
            args += listOf("--tmpfs", "$werkatorDir")
        }
        args += listOf("--bind", "$adminDir", "$adminDir")
        return args
    }

    private fun buildEnvRoot(repoDir: Path): Path = repoDir.resolve(BUILDENV_DIR)

    /** A short hash of the archive source, so a changed source unpacks a fresh rootfs. */
    private fun envKey(rootfs: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(rootfs.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)

    companion object {
        const val BUILDENV_DIR = ".git/werkator/buildenv"
        const val ROOTFS_DIR = "rootfs"
        const val HOME_DIR = "home"
    }
}
