package de.hoennig.werkator.server

import de.hoennig.werkator.build.ArtifactKeys
import de.hoennig.werkator.build.DockerBuildRunner.Companion.WERKATOR_LABEL
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.git.GitCommandRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Manages the opt-in nginx+certbot Docker container that serves Werkator over
 * HTTPS on hosts without a reverse proxy (ADR 0005), ported from the legacy
 * `start_artifact_nginx` subsystem. Shells out to the `docker` CLI via the
 * generic [GitCommandRunner] process wrapper, like [de.hoennig.werkator.build.DockerBuildRunner].
 *
 * Startup is two-phase: an HTTP-only init config serves the ACME webroot
 * challenge, the certificate is obtained via a certbot container, then nginx is
 * restarted with the full HTTPS config. All failures are non-fatal warnings —
 * the plain HTTP server keeps running without the proxy (legacy behavior).
 * Nothing runs unless [start] is called (server profile only, see [ServerNginxLifecycle]).
 */
@Component
class NginxProxyManager(
    private val commandRunner: GitCommandRunner,
    private val configLoader: ConfigLoader,
) {
    private val log = LoggerFactory.getLogger(NginxProxyManager::class.java)

    var workingDir: Path = Paths.get(".")

    /** Replaceable for tests: the wait between port-conflict re-checks. */
    internal var sleeper: (millis: Long) -> Unit = Thread::sleep

    /** The settings the running container was started with; null while no container is managed. */
    private var running: NginxSettings? = null

    fun isEnabled(): Boolean =
        configLoader
            .load(workingDir)
            .server.nginx.enabled

    /**
     * Legacy `start_artifact_nginx`: prepare the state dir, remove stale containers,
     * run nginx (init config first when no certificate exists yet), obtain or renew
     * the certificate, then restart with the full HTTPS config.
     */
    @Synchronized
    fun start() {
        try {
            val settings = resolveSettings() ?: return
            prepareStateDirs(settings)
            writeSslOptions(settings)
            cleanupStaleContainers(settings)
            if (!waitForPortsFree(settings)) {
                log.warn("managed nginx was not started because a configured port is still in use")
                return
            }
            writeNginxConf(settings, full = Files.exists(settings.certFile))
            if (!runContainer(settings)) {
                return
            }
            if (!obtainOrRenewCertificate(settings)) {
                log.warn("managed nginx is running, but Let's Encrypt certificate setup failed")
                return
            }
            writeNginxConf(settings, full = true)
            if (!runContainer(settings)) {
                log.warn("certificate is available, but restarting managed nginx with HTTPS failed")
                return
            }
            log.info(
                "managed nginx proxy: https://{}:{}/ -> http://{}:{}/ (state: {})",
                settings.serverName,
                settings.httpsPort,
                settings.upstreamHost,
                settings.upstreamPort,
                settings.stateDir,
            )
        } catch (e: Exception) {
            log.warn("could not start managed nginx: {}", e.toString())
        }
    }

    /**
     * Renews the certificate and reloads nginx; scheduled daily by [ServerNginxLifecycle].
     * Improvement over legacy, which renewed only at process start and relied on
     * frequent self-update restarts. No-op while no container is managed.
     */
    @Synchronized
    fun renewCertificateAndReload() {
        val settings = running ?: return
        try {
            val renew = commandRunner.run(certbotArgs(settings) + listOf("renew", "-q"), workingDir)
            if (!renew.isSuccess) {
                log.warn("certificate renewal failed: {}", renew.stderr.trim())
                return
            }
            val reload = commandRunner.run(listOf("docker", "exec", settings.containerName, "nginx", "-s", "reload"), workingDir)
            if (!reload.isSuccess) {
                log.warn("certificate renewed, but nginx reload failed: {}", reload.stderr.trim())
            }
        } catch (e: Exception) {
            log.warn("certificate renewal check failed: {}", e.toString())
        }
    }

    /** Removes the managed container (legacy shutdown cleanup); safe to call when none runs. */
    @Synchronized
    fun stop() {
        val settings = running ?: return
        running = null
        try {
            commandRunner.run(listOf("docker", "rm", "-f", settings.containerName), workingDir)
        } catch (e: Exception) {
            log.warn("could not remove managed nginx container {}: {}", settings.containerName, e.toString())
        }
    }

    /**
     * Legacy `configure_artifact_nginx_defaults` plus validation: resolves the
     * effective settings or returns null (with a warning) when misconfigured.
     * Host names are restricted to [HOST_NAME_PATTERN] so they substitute safely
     * into the nginx config.
     */
    internal fun resolveSettings(): NginxSettings? {
        val config = configLoader.load(workingDir)
        val nginx = config.server.nginx
        if (!nginx.enabled) {
            return null
        }
        if (nginx.serverName.isBlank()) {
            log.warn("cannot start managed nginx because server.nginx.serverName is empty")
            return null
        }
        val upstreamHost = nginx.upstreamHost.ifBlank { nginx.serverName }
        if (!HOST_NAME_PATTERN.matches(nginx.serverName) || !HOST_NAME_PATTERN.matches(upstreamHost)) {
            log.warn("cannot start managed nginx because serverName or upstreamHost is not a valid host name")
            return null
        }
        if (nginx.httpPort !in 1..65535 || nginx.httpsPort !in 1..65535 || nginx.httpPort == nginx.httpsPort) {
            log.warn("cannot start managed nginx because the nginx ports are invalid")
            return null
        }
        if (config.server.port == nginx.httpPort || config.server.port == nginx.httpsPort) {
            log.warn("cannot start managed nginx because server.port {} collides with an nginx port", config.server.port)
            return null
        }
        val repoDir = workingDir.toAbsolutePath().normalize()
        val stateDir =
            if (nginx.stateDir.isNotBlank()) {
                repoDir.resolve(expandHome(nginx.stateDir)).normalize()
            } else {
                defaultStateDir(repoDir)
            }
        return NginxSettings(
            serverName = nginx.serverName,
            httpPort = nginx.httpPort,
            httpsPort = nginx.httpsPort,
            upstreamHost = upstreamHost,
            upstreamPort = config.server.port,
            containerName = nginx.containerName.ifBlank { defaultContainerName(repoDir) },
            stateDir = stateDir,
            letsencryptEmail = nginx.letsencryptEmail,
            repoKey = ArtifactKeys.repoKey(repoDir),
        )
    }

    private fun prepareStateDirs(settings: NginxSettings) {
        Files.createDirectories(settings.certbotConf)
        Files.createDirectories(settings.certbotWww)
        Files.createDirectories(settings.certbotLog)
        Files.createDirectories(settings.nginxLog)
    }

    /** Legacy `artifact_nginx_write_ssl_options`: the certbot nginx snippet plus its pinned DH parameters. */
    private fun writeSslOptions(settings: NginxSettings) {
        Files.writeString(settings.certbotConf.resolve("options-ssl-nginx.conf"), NginxConfigFiles.SSL_OPTIONS)
        val dhParams = settings.certbotConf.resolve("ssl-dhparams.pem")
        if (!Files.exists(dhParams)) {
            writeBundledDhParams(dhParams)
        }
    }

    private fun writeNginxConf(
        settings: NginxSettings,
        full: Boolean,
    ) {
        Files.writeString(
            settings.nginxConf,
            NginxConfigFiles.nginxConf(settings.serverName, settings.upstreamHost, settings.upstreamPort, full),
        )
    }

    /**
     * Legacy `cleanup_stale_artifact_nginx_containers`: remove the container by
     * name, all nginx-role containers of this repository by label, and any
     * Werkator container still occupying the configured ports.
     */
    private fun cleanupStaleContainers(settings: NginxSettings) {
        commandRunner.run(listOf("docker", "rm", "-f", settings.containerName), workingDir)
        val labelled =
            commandRunner.run(
                listOf(
                    "docker",
                    "ps",
                    "-aq",
                    "--filter",
                    "label=$WERKATOR_LABEL=true",
                    "--filter",
                    "label=$WERKATOR_LABEL.repository=${settings.repoKey}",
                    "--filter",
                    "label=$WERKATOR_LABEL.role=nginx",
                ),
                workingDir,
            )
        if (labelled.isSuccess && labelled.lines().isNotEmpty()) {
            commandRunner.run(listOf("docker", "rm", "-f") + labelled.lines(), workingDir)
        }
        for (container in listContainersUsingPorts(settings)) {
            if (container.labels.contains("$WERKATOR_LABEL=true") ||
                container.name.startsWith("werkator-") ||
                container.name.startsWith("git-watch-origin-and-test-nginx-")
            ) {
                log.info("removing stale Werkator container using an nginx port: {}", container.name)
                commandRunner.run(listOf("docker", "rm", "-f", container.id), workingDir)
            }
        }
    }

    /** Legacy `wait_for_artifact_nginx_ports`: re-check up to four times, warning about foreign owners. */
    private fun waitForPortsFree(settings: NginxSettings): Boolean {
        repeat(4) {
            val owners = listContainersUsingPorts(settings)
            if (owners.isEmpty()) {
                return true
            }
            owners.forEach { log.warn("nginx port is already used by Docker container {} ({})", it.name, it.id) }
            sleeper(1000)
        }
        return listContainersUsingPorts(settings).isEmpty()
    }

    private data class ContainerInfo(
        val id: String,
        val name: String,
        val ports: String,
        val labels: String,
    )

    private fun listContainersUsingPorts(settings: NginxSettings): List<ContainerInfo> {
        val listing =
            commandRunner.run(
                listOf("docker", "ps", "--format", "{{.ID}}\t{{.Names}}\t{{.Ports}}\t{{.Labels}}"),
                workingDir,
            )
        if (!listing.isSuccess) {
            return emptyList()
        }
        return listing
            .lines()
            .mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size < 3) null else ContainerInfo(fields[0], fields[1], fields[2], fields.getOrElse(3) { "" })
            }.filter { container ->
                listOf(settings.httpPort, settings.httpsPort).any { container.ports.contains(":$it->") }
            }
    }

    /** Legacy `artifact_nginx_run_container`; replaces any previous instance of the container. */
    private fun runContainer(settings: NginxSettings): Boolean {
        commandRunner.run(listOf("docker", "rm", "-f", settings.containerName), workingDir)
        val run = commandRunner.run(runContainerArgs(settings), workingDir)
        if (!run.isSuccess) {
            log.warn("could not start managed nginx container {}: {}", settings.containerName, run.stderr.trim())
            return false
        }
        running = settings
        return true
    }

    internal fun runContainerArgs(settings: NginxSettings): List<String> =
        listOf(
            "docker",
            "run",
            "-d",
            "--name",
            settings.containerName,
            "--publish",
            "${settings.httpPort}:80",
            "--publish",
            "${settings.httpsPort}:443",
            "--network",
            "bridge",
            "--volume",
            "${settings.certbotConf}:/etc/letsencrypt",
            "--volume",
            "${settings.certbotWww}:/var/www/certbot",
            "--volume",
            "${settings.nginxLog}:/var/log/nginx",
            "--volume",
            "${settings.nginxConf}:/etc/nginx/nginx.conf:ro",
            "--label",
            "$WERKATOR_LABEL=true",
            "--label",
            "$WERKATOR_LABEL.repository=${settings.repoKey}",
            "--label",
            "$WERKATOR_LABEL.role=nginx",
            "nginx",
        )

    /**
     * Legacy `artifact_nginx_obtain_or_renew_certificate`: `certonly` in webroot
     * mode for a first certificate, plain `renew` when one already exists (nginx
     * is already running and serves the challenge directory).
     */
    private fun obtainOrRenewCertificate(settings: NginxSettings): Boolean {
        val args =
            if (Files.exists(settings.certFile)) {
                certbotArgs(settings) + listOf("renew", "-q")
            } else {
                obtainCertificateArgs(settings)
            }
        val result = commandRunner.run(args, workingDir)
        if (!result.isSuccess) {
            log.warn("certbot failed: {}", result.stderr.trim())
        }
        return result.isSuccess
    }

    internal fun obtainCertificateArgs(settings: NginxSettings): List<String> {
        val emailArgs =
            if (settings.letsencryptEmail.isNotBlank()) {
                listOf("--email", settings.letsencryptEmail)
            } else {
                listOf("--register-unsafely-without-email")
            }
        return certbotArgs(settings) +
            listOf(
                "certonly",
                "--webroot",
                "--webroot-path",
                "/var/www/certbot",
                "--cert-name",
                settings.serverName,
                "-d",
                settings.serverName,
                "--rsa-key-size",
                "4096",
                "--non-interactive",
                "--agree-tos",
            ) + emailArgs
    }

    private fun certbotArgs(settings: NginxSettings): List<String> =
        listOf(
            "docker",
            "run",
            "--rm",
            "--volume",
            "${settings.certbotConf}:/etc/letsencrypt",
            "--volume",
            "${settings.certbotWww}:/var/www/certbot",
            "--volume",
            "${settings.certbotLog}:/var/log/letsencrypt",
            "certbot/certbot",
        )

    private fun expandHome(path: String): Path =
        if (path == "~" || path.startsWith("~/")) {
            Paths.get(System.getProperty("user.home"), path.removePrefix("~"))
        } else {
            Paths.get(path)
        }

    /** The effective managed-nginx settings with all defaults resolved. */
    internal data class NginxSettings(
        val serverName: String,
        val httpPort: Int,
        val httpsPort: Int,
        val upstreamHost: String,
        val upstreamPort: Int,
        val containerName: String,
        val stateDir: Path,
        val letsencryptEmail: String,
        val repoKey: String,
    ) {
        val certbotConf: Path get() = stateDir.resolve("certbot/conf")
        val certbotWww: Path get() = stateDir.resolve("certbot/www")
        val certbotLog: Path get() = stateDir.resolve("certbot/log")
        val nginxLog: Path get() = stateDir.resolve("nginx/log")
        val nginxConf: Path get() = stateDir.resolve("nginx/nginx.conf")
        val certFile: Path get() = certbotConf.resolve("live/$serverName/fullchain.pem")
    }

    companion object {
        /** Host names substitute unescaped into the nginx config, so only safe characters are allowed. */
        internal val HOST_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9.-]*")

        /** Legacy `artifact_nginx_default_state_dir`. */
        fun defaultStateDir(repoDir: Path): Path {
            val stateHome =
                System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                    ?: Paths.get(System.getProperty("user.home"), ".local", "state")
            return stateHome
                .resolve("werkator")
                .resolve("nginx")
                .resolve(ArtifactKeys.repoKey(repoDir))
                .toAbsolutePath()
                .normalize()
        }

        /** Legacy default `werkator-nginx-<repo-name>` with unsafe characters replaced. */
        fun defaultContainerName(repoDir: Path): String =
            "werkator-nginx-" + repoDir.fileName.toString().replace(Regex("[^A-Za-z0-9_.-]"), "-")

        /**
         * Certbot's pinned DH parameters (RFC 7919 ffdhe2048), bundled as a resource:
         * certbot removed the file from its repository, so it can no longer be downloaded.
         */
        private fun writeBundledDhParams(target: Path) {
            val resource =
                checkNotNull(NginxProxyManager::class.java.getResourceAsStream("/nginx/ssl-dhparams.pem")) {
                    "bundled nginx/ssl-dhparams.pem resource is missing"
                }
            resource.use { Files.copy(it, target) }
        }
    }
}
