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
 * Runs build commands inside a bubblewrap user-namespace sandbox (Step 17 / ADR 0008),
 * for hosts without root and without a Docker daemon (e.g. Hostsharing managed
 * webspaces). Since step 21 session C it no longer assembles the raw `bwrap` argv:
 * it shells out to the `werkdock` CLI (`bwrap.werkdock`, default via PATH) — the same
 * pattern as git and docker, CLI, no library.
 *
 * The rootfs archive becomes a werkdock *image*, loaded once per source
 * (`werkator-buildenv-<hash>`, the hash over the source string, so a changed source
 * loads a fresh image) into werkdock's own store (`$WERKDOCK_HOME`, default
 * `~/.werkdock`) — shared by every repository of this OS user, unlike the old
 * per-repo unpack. Only the download cache for URL sources and the persistent
 * toolchain home (bound to `/root` for Gradle/Go caches) stay under
 * `.git/werkator/buildenv/`.
 *
 * Werkdock clears the environment inside the sandbox (docker semantics), so the
 * server's environment no longer leaks in — only the explicit `-e` variables below
 * plus werkdock's own `HOME`/`PATH` exist inside; the pam_tmpdir TMPDIR class of
 * bugs is gone by construction. Git works inside the sandbox with the same layered
 * mounts as the Docker runner, expressed as werkdock flags whose order is
 * significant and preserved: read-only `.git`, tmpfs mask over `.git/werkator`,
 * read-write worktree admin dir — see [gitMetadataMounts]. The returned [Process]
 * is the attached `werkdock run`, whose `bwrap` child dies with it
 * (`--die-with-parent`), so log streaming and cancellation work exactly like
 * native builds.
 */
@Component
class BwrapBuildRunner(
    private val commandRunner: GitCommandRunner,
) : BuildRunner {
    private val log = LoggerFactory.getLogger(BwrapBuildRunner::class.java)

    /** Replaceable process launcher so unit tests can capture the assembled `werkdock` argv. */
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
        val werkdock = bwrap.werkdock.ifBlank { "werkdock" }
        val image = imageName(bwrap.rootfs)
        ensureImage(werkdock, image, bwrap, repoDir, onAuxProcess)
        val homeDir = repoDir.resolve(BUILDENV_DIR).resolve(HOME_DIR)
        Files.createDirectories(homeDir)
        val args = invocation(command, workingDir, environment, repoDir, bwrap, werkdock, image, homeDir)
        return processStarter(args, repoDir)
    }

    /**
     * Loads the rootfs archive into the werkdock image store once per source.
     * `werkdock images` answers existence through the CLI, like `docker image
     * inspect` does for the Docker runner.
     */
    private fun ensureImage(
        werkdock: String,
        image: String,
        bwrap: BwrapConfig,
        repoDir: Path,
        onAuxProcess: (Process) -> Unit,
    ) {
        val loaded = commandRunner.runOrThrow(listOf(werkdock, "images"), repoDir, onProcess = onAuxProcess).lines()
        if (image in loaded) {
            return
        }
        val envDir = repoDir.resolve(BUILDENV_DIR).resolve(sourceKey(bwrap.rootfs))
        Files.createDirectories(envDir)
        val archive = localArchive(bwrap.rootfs, envDir, repoDir, onAuxProcess)
        log.info("loading build environment {} as werkdock image {}", bwrap.rootfs, image)
        commandRunner.runOrThrow(
            listOf(werkdock, "load", "-i", archive, "--name", image),
            repoDir,
            onProcess = onAuxProcess,
        )
    }

    /**
     * Resolves [BwrapConfig.rootfs] to a local archive path: a bare or `file:` path is
     * used as-is; an `http(s)` URL is downloaded once into the buildenv cache.
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
        werkdock: String,
        image: String,
        homeDir: Path,
    ): List<String> {
        // Mounts at absolute host paths — same contract as the Docker runner.
        // Relative paths come from the CLI relative to the repo, so resolve them
        // against repoDir, not against the process working directory.
        val repoDirAbs = repoDir.toAbsolutePath().normalize()
        val workspaceAbs =
            if (workspace.isAbsolute) workspace.normalize() else repoDirAbs.resolve(workspace).normalize()
        val homeDirAbs =
            if (homeDir.isAbsolute) homeDir.normalize() else repoDirAbs.resolve(homeDir).normalize()
        val args = mutableListOf(werkdock, "run", "--rm")
        // The repo read-write FIRST, as the base the later mountpoints (workspace,
        // worktree admin dir) are created in; the git metadata mounts then layer
        // the isolation on top, and the workspace bind last shadows the tmpfs mask
        // at exactly its own path (it lives under .git/werkator/worktrees).
        args += listOf("-v", "$repoDirAbs:$repoDirAbs")
        args += gitMetadataMounts(workspaceAbs, repoDir)
        args += listOf("-v", "$workspaceAbs:$workspaceAbs")
        args += listOf("-v", "$homeDirAbs:/root")
        for ((key, value) in environment) {
            args += listOf("-e", "$key=$value")
        }
        for ((key, value) in bwrap.env) {
            args += listOf("-e", "$key=$value")
        }
        args += listOf("-w", "$workspaceAbs")
        args += image
        args += listOf("/bin/sh", "-c", command)
        return args
    }

    /**
     * Makes git work inside the sandbox without exposing Werkator's secrets — the same
     * three layered mounts as the Docker runner, expressed as werkdock flags (werkdock
     * preserves the -v/--tmpfs flag order, and bwrap nests mounts by target path): the
     * primary `.git` read-only, an empty tmpfs masking `.git/werkator/` (machine config
     * with `git.token`, control token, build state), and this worktree's admin directory
     * read-write so index-refreshing commands keep working. Object and ref writes stay
     * blocked by the read-only `.git` mount. No mounts are added when the workspace is
     * not a worktree of [repoDir].
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
        val args = mutableListOf("-v", "$gitDir:$gitDir:ro")
        val werkatorDir = gitDir.resolve("werkator")
        if (Files.isDirectory(werkatorDir)) {
            args += listOf("--tmpfs", "$werkatorDir")
        }
        args += listOf("-v", "$adminDir:$adminDir")
        return args
    }

    /** A short hash of the archive source, so a changed source loads a fresh image. */
    private fun sourceKey(rootfs: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(rootfs.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)

    private fun imageName(rootfs: String): String = "werkator-buildenv-${sourceKey(rootfs)}"

    companion object {
        const val BUILDENV_DIR = ".git/werkator/buildenv"
        const val HOME_DIR = "home"
    }
}
