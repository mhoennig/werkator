package de.hoennig.gittally.build

import de.hoennig.gittally.config.BranchConfig
import de.hoennig.gittally.config.DockerConfig
import de.hoennig.gittally.git.GitCommandRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
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
 * executor streams and terminates it like a native build. Ownership of `build/`
 * and `.gradle/` is repaired to the host user inside the same container run.
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
    ): Process {
        val docker = branchConfig.docker
        require(docker.image.isNotBlank()) { "branches.<name>.docker.image must be set when docker.enabled is true" }
        val repoKey = ArtifactKeys.repoKey(repoDir)
        cleanupStaleContainersOnce(repoKey, repoDir)
        ensureImage(docker, repoDir)
        val uid = id("-u", repoDir)
        val gid = id("-g", repoDir)
        val volume = gradleVolumeName(repoKey)
        prepareGradleVolume(volume, docker.image, uid, gid, repoDir)
        val containerName = containerName(repoKey, environment["branch"])
        removeContainer(containerName, repoDir)
        val runCommand =
            runArgs(
                command = command,
                workspace = workingDir.toAbsolutePath().normalize(),
                environment = environment,
                docker = docker,
                repoKey = repoKey,
                containerName = containerName,
                volume = volume,
                uid = uid,
                gid = gid,
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
                "org.gittally.dockerfile=${docker.dockerfile}",
                "--label",
                "org.gittally.dockerfile-sha256=$dockerfileHash",
                "--label",
                "org.gittally.build-context=${docker.context}",
                "--label",
                "${DockerImageInputs.INPUTS_LABEL}=$inputsHash",
                "-t",
                docker.image,
                "-f",
                docker.dockerfile,
                docker.context,
            ),
            repoDir,
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
                    "label=$GITTALLY_LABEL=true",
                    "--filter",
                    "label=$GITTALLY_LABEL.repository=$repoKey",
                    "--filter",
                    "label=$GITTALLY_LABEL.role=build",
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
        repoKey: String,
        containerName: String,
        volume: String,
        uid: String,
        gid: String,
    ): List<String> {
        val socket = socketLocator.locate(uid)
        val args = mutableListOf("docker", "run", "--rm", "--init", "--name", containerName)
        args +=
            listOf(
                "--label",
                "$GITTALLY_LABEL=true",
                "--label",
                "$GITTALLY_LABEL.repository=$repoKey",
                "--label",
                "$GITTALLY_LABEL.role=build",
            )
        args += listOf("--workdir", "$workspace", "--volume", "$workspace:$workspace")
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
        args += listOf("--user", if (socket?.rootless == true) uid else "0")
        val hostOverride = if (docker.network == "host") "localhost" else "host.docker.internal"
        args += listOf("--env", "TESTCONTAINERS_HOST_OVERRIDE=$hostOverride")
        if (docker.network != "host") {
            args += listOf("--add-host", "host.docker.internal:host-gateway")
        }
        args += docker.image
        args += listOf("sh", "-c", OWNERSHIP_REPAIR_SCRIPT, "sh", uid, gid, command)
        return args
    }

    private fun id(
        flag: String,
        repoDir: Path,
    ): String = commandRunner.runOrThrow(listOf("id", flag), repoDir).stdout.trim()

    companion object {
        /** Container label namespace; legacy used `org.hostsharing.gittally`. */
        const val GITTALLY_LABEL = "org.hoennig.gittally"

        fun gradleVolumeName(repoKey: String): String = "gittally-gradle-$repoKey"

        fun containerName(
            repoKey: String,
            branch: String?,
        ): String = "gittally-build-$repoKey" + (branch?.let { "-${ArtifactKeys.branchKey(it)}" } ?: "")

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
