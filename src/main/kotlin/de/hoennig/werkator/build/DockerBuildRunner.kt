package de.hoennig.werkator.build

import de.hoennig.werkator.config.BranchConfig
import de.hoennig.werkator.config.DockerConfig
import de.hoennig.werkator.git.GitCommandRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs build commands inside a Docker container (legacy `--docker` mode), shelling
 * out to the `docker` CLI via the generic [GitCommandRunner] process wrapper —
 * consistent with the git gateway; no Docker SDK dependency.
 *
 * Auxiliary steps run synchronously before each command: stale container cleanup
 * (once per process), image ensure (rebuild when the Dockerfile inputs changed,
 * tracked via the [DockerImageInputs.INPUTS_LABEL] image label), and the per-repo
 * Gradle cache volume. The returned [Process] is the attached `docker run` client
 * (`--init`, so termination signals reach the build inside the container); the
 * executor streams and terminates it like a native build. On rootful daemons the
 * ownership of `build/` and `.gradle/` is repaired to the host user inside the
 * same container run; under a rootless daemon the container runs as root, which
 * already is the host user, so the repair chown degenerates to `0:0`.
 * Git works inside the container: the primary `.git` is mounted read-only with
 * `.git/werkator/` masked, see [gitMetadataMounts].
 */
@Component
class DockerBuildRunner(
    private val commandRunner: GitCommandRunner,
    private val socketLocator: DockerSocketLocator,
) : BuildRunner {
    private val log = LoggerFactory.getLogger(DockerBuildRunner::class.java)

    /** Volumes already prepared by this process, keyed by volume and image. */
    private val preparedVolumes = mutableSetOf<String>()

    private var staleContainersCleaned = false

    /** Replaceable process launcher so unit tests can capture the assembled `docker run` argv. */
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
        val docker = branchConfig.docker
        require(docker.image.isNotBlank()) { "branches.<name>.docker.image must be set when docker.enabled is true" }
        val repoKey = ArtifactKeys.repoKey(repoDir)
        cleanupStaleContainersOnce(repoKey, repoDir)
        ensureImage(docker, repoDir, onAuxProcess)
        val uid = id("-u", repoDir)
        val gid = id("-g", repoDir)
        val socket = socketLocator.locate(uid)
        // Under a rootless daemon, container root IS the unprivileged host user (identity mapping),
        // so files in the bind-mounted worktree stay host-owned and no chown is needed; chowning to
        // the host ids there would push the files into the subuid range and lock the host user out.
        val rootless = socket?.rootless == true
        val ownershipUid = if (rootless) "0" else uid
        val ownershipGid = if (rootless) "0" else gid
        val volume = gradleVolumeName(repoKey)
        prepareGradleVolume(volume, docker.image, ownershipUid, ownershipGid, repoDir, onAuxProcess)
        val containerName = containerName(repoKey, environment["branch"])
        removeContainer(containerName, repoDir)
        val runCommand =
            runArgs(
                command = command,
                workspace = workingDir.toAbsolutePath().normalize(),
                environment = environment,
                docker = docker,
                repoDir = repoDir,
                repoKey = repoKey,
                containerName = containerName,
                volume = volume,
                socket = socket,
                uid = ownershipUid,
                gid = ownershipGid,
            )
        return processStarter(runCommand, repoDir)
    }

    /**
     * Legacy `ensure_docker_build_image`: without a configured Dockerfile the image
     * is used as-is (pulled by `docker run` on demand); otherwise it is (re)built
     * whenever it is missing or its build-inputs label no longer matches.
     */
    private fun ensureImage(
        docker: DockerConfig,
        repoDir: Path,
        onAuxProcess: (Process) -> Unit,
    ) {
        if (docker.dockerfile.isBlank()) {
            return
        }
        val dockerfileHash = DockerImageInputs.dockerfileSha256(repoDir.resolve(docker.dockerfile))
        val inputsHash = DockerImageInputs.inputsSha256(dockerfileHash, docker.dockerfile, docker.context)
        val inspect =
            commandRunner.run(
                listOf("docker", "image", "inspect", docker.image, "--format", INSPECT_INPUTS_LABEL_FORMAT),
                repoDir,
            )
        if (inspect.isSuccess && inspect.stdout.trim() == inputsHash) {
            return
        }
        log.info("building Docker image {} from {}", docker.image, docker.dockerfile)
        commandRunner.runOrThrow(
            listOf(
                "docker",
                "build",
                "--label",
                "org.werkator.dockerfile=${docker.dockerfile}",
                "--label",
                "org.werkator.dockerfile-sha256=$dockerfileHash",
                "--label",
                "org.werkator.build-context=${docker.context}",
                "--label",
                "${DockerImageInputs.INPUTS_LABEL}=$inputsHash",
                "-t",
                docker.image,
                "-f",
                docker.dockerfile,
                docker.context,
            ),
            repoDir,
            onProcess = onAuxProcess,
        )
    }

    /**
     * Legacy `prepare_docker_gradle_volume`: create the per-repo Gradle cache volume
     * and chown it to the host user — once per process and image.
     */
    @Synchronized
    private fun prepareGradleVolume(
        volume: String,
        image: String,
        uid: String,
        gid: String,
        repoDir: Path,
        onAuxProcess: (Process) -> Unit,
    ) {
        val key = "$volume@$image"
        if (key in preparedVolumes) {
            return
        }
        commandRunner.runOrThrow(listOf("docker", "volume", "create", volume), repoDir)
        commandRunner.runOrThrow(
            listOf(
                "docker",
                "run",
                "--rm",
                "--user",
                "0",
                "--volume",
                "$volume:/gradle-user-home",
                image,
                "sh",
                "-c",
                VOLUME_PREPARE_SCRIPT,
                "sh",
                uid,
                gid,
            ),
            repoDir,
            onProcess = onAuxProcess,
        )
        preparedVolumes += key
    }

    /**
     * Legacy `cleanup_stale_build_runtime`, run before the first Docker build of this
     * process instead of at daemon startup, so hosts that never build in Docker never
     * see a docker call: force-remove all leftover build containers of this repository.
     */
    @Synchronized
    private fun cleanupStaleContainersOnce(
        repoKey: String,
        repoDir: Path,
    ) {
        if (staleContainersCleaned) {
            return
        }
        staleContainersCleaned = true
        val listing =
            commandRunner.run(
                listOf(
                    "docker",
                    "ps",
                    "-aq",
                    "--filter",
                    "label=$WERKATOR_LABEL=true",
                    "--filter",
                    "label=$WERKATOR_LABEL.repository=$repoKey",
                    "--filter",
                    "label=$WERKATOR_LABEL.role=build",
                ),
                repoDir,
            )
        if (!listing.isSuccess) {
            log.warn("could not list stale build containers: {}", listing.stderr.trim())
            return
        }
        val containers = listing.lines()
        if (containers.isEmpty()) {
            return
        }
        log.info("removing {} stale build container(s)", containers.size)
        commandRunner.run(listOf("docker", "rm", "-f") + containers, repoDir)
    }

    private fun removeContainer(
        containerName: String,
        repoDir: Path,
    ) {
        commandRunner.run(listOf("docker", "rm", "-f", containerName), repoDir)
    }

    private fun runArgs(
        command: String,
        workspace: Path,
        environment: Map<String, String>,
        docker: DockerConfig,
        repoDir: Path,
        repoKey: String,
        containerName: String,
        volume: String,
        socket: DockerSocket?,
        uid: String,
        gid: String,
    ): List<String> {
        val args = mutableListOf("docker", "run", "--rm", "--init", "--name", containerName)
        args +=
            listOf(
                "--label",
                "$WERKATOR_LABEL=true",
                "--label",
                "$WERKATOR_LABEL.repository=$repoKey",
                "--label",
                "$WERKATOR_LABEL.role=build",
            )
        args += listOf("--workdir", "$workspace", "--volume", "$workspace:$workspace")
        args += gitMetadataMounts(workspace, repoDir)
        args += listOf("--volume", "$volume:/gradle-user-home")
        args += listOf("--env", "HOME=/tmp/docker-home", "--env", "GRADLE_USER_HOME=/gradle-user-home")
        for ((key, value) in environment) {
            args += listOf("--env", "$key=$value")
        }
        for ((key, value) in docker.env) {
            args += listOf("--env", "$key=$value")
        }
        if (docker.network.isNotBlank()) {
            args += listOf("--network", docker.network)
        }
        if (socket != null) {
            args += listOf("--volume", "${socket.path}:/var/run/docker.sock")
            args += listOf("--env", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock")
            args += listOf("--env", "DOCKER_HOST=unix:///var/run/docker.sock")
            if (!socket.rootless && socket.gid != null) {
                args += listOf("--group-add", socket.gid.toString())
            }
        }
        // root in the container: the real host user under a rootless daemon (identity mapping),
        // or actual root under a rootful daemon, where the ownership-repair chown fixes the files.
        args += listOf("--user", "0")
        val hostOverride = if (docker.network == "host") "localhost" else "host.docker.internal"
        args += listOf("--env", "TESTCONTAINERS_HOST_OVERRIDE=$hostOverride")
        if (docker.network != "host") {
            args += listOf("--add-host", "host.docker.internal:host-gateway")
        }
        args += docker.image
        args += listOf("sh", "-c", OWNERSHIP_REPAIR_SCRIPT, "sh", uid, gid, command)
        return args
    }

    /**
     * Makes git work inside the build container without exposing Werkator's secrets.
     *
     * The workspace is a git worktree whose `.git` file points into the primary
     * repository's `.git`, which is not part of the workspace mount — so any git call
     * in the build would fail. Three layered mounts fix that (Docker nests mounts by
     * target path): the primary `.git` read-only, an empty tmpfs masking `.git/werkator/`
     * (machine config with `git.token`, control token, build state — the workspace bind
     * resurfaces only this build's own worktree inside it), and this worktree's admin
     * directory read-write, so index-refreshing commands like `git status` keep working.
     * Object and ref writes stay blocked by the read-only `.git` mount.
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
        val args = mutableListOf("--volume", "$gitDir:$gitDir:ro")
        val werkatorDir = gitDir.resolve("werkator")
        if (Files.isDirectory(werkatorDir)) {
            args += listOf("--tmpfs", "$werkatorDir")
        }
        args += listOf("--volume", "$adminDir:$adminDir")
        return args
    }

    private fun id(
        flag: String,
        repoDir: Path,
    ): String = commandRunner.runOrThrow(listOf("id", flag), repoDir).stdout.trim()

    companion object {
        /** Container label namespace; legacy used `org.hostsharing.werkator`. */
        const val WERKATOR_LABEL = "org.hoennig.werkator"

        fun gradleVolumeName(repoKey: String): String = "werkator-gradle-$repoKey"

        fun containerName(
            repoKey: String,
            branch: String?,
        ): String = "werkator-build-$repoKey" + (branch?.let { "-${ArtifactKeys.branchKey(it)}" } ?: "")

        private val INSPECT_INPUTS_LABEL_FORMAT = """{{ index .Config.Labels "${DockerImageInputs.INPUTS_LABEL}" }}"""

        /** Legacy `prepare_docker_gradle_volume` container script, verbatim. */
        private const val VOLUME_PREPARE_SCRIPT =
            "mkdir -p /gradle-user-home/wrapper/dists && chown -R \"\$1:\$2\" /gradle-user-home && chmod -R u+rwX /gradle-user-home"

        /**
         * Runs the build command and then repairs the workspace ownership (legacy
         * `repair_docker_workspace_ownership`) inside the same container, preserving
         * the build's exit code.
         */
        private const val OWNERSHIP_REPAIR_SCRIPT =
            "bash -c \"\$3\"; build_exit=\$?; " +
                "for path in build .gradle; do " +
                "if [ -e \"\$path\" ]; then chown -R \"\$1:\$2\" \"\$path\" && chmod -R u+rwX \"\$path\"; fi; " +
                "done; " +
                "exit \$build_exit"
    }
}
