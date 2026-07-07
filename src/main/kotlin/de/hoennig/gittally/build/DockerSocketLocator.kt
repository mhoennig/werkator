package de.hoennig.gittally.build

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The host-side Docker socket a build container should talk to. */
data class DockerSocket(
    val path: Path,
    /** True for a rootless daemon socket (`/run/user/<uid>/docker.sock`); the container then runs as the host user. */
    val rootless: Boolean,
    /** Group id owning the socket, for `--group-add` on rootful daemons; null when unavailable. */
    val gid: Long?,
)

/**
 * Port of the legacy socket detection: a unix `DOCKER_HOST` wins, then the rootless
 * user socket, then the system socket. Returns null when no unix socket exists
 * (e.g. a tcp:// daemon); the build container then runs without a socket mount.
 */
@Component
class DockerSocketLocator {
    fun locate(uid: String): DockerSocket? {
        val dockerHost = System.getenv("DOCKER_HOST").orEmpty()
        val rootlessPath = Paths.get("/run/user/$uid/docker.sock")
        val path =
            when {
                dockerHost.startsWith("unix://") -> Paths.get(dockerHost.removePrefix("unix://"))
                dockerHost.isNotEmpty() -> return null
                Files.exists(rootlessPath) -> rootlessPath
                else -> Paths.get("/var/run/docker.sock")
            }
        if (!Files.exists(path)) {
            return null
        }
        return DockerSocket(path = path, rootless = path == rootlessPath, gid = socketGid(path))
    }

    private fun socketGid(path: Path): Long? =
        try {
            (Files.getAttribute(path, "unix:gid") as? Number)?.toLong()
        } catch (_: Exception) {
            null
        }
}
