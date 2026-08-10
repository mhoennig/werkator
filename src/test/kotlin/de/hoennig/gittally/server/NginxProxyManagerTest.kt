package de.hoennig.gittally.server

import de.hoennig.gittally.build.ArtifactKeys
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.config.GitTallyConfig
import de.hoennig.gittally.config.NginxConfig
import de.hoennig.gittally.config.ServerConfig
import de.hoennig.gittally.git.GitCommandResult
import de.hoennig.gittally.git.GitCommandRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path

class NginxProxyManagerTest : FunSpec() {
    private val commandRunner = mockk<GitCommandRunner>()
    private val configLoader = mockk<ConfigLoader>()
    private lateinit var manager: NginxProxyManager
    private lateinit var repoDir: Path
    private lateinit var stateDir: Path
    private val captured = mutableListOf<List<String>>()
    private val configsAtContainerRun = mutableListOf<String>()
    private var sleepCount = 0

    private fun nginxConfig(
        enabled: Boolean = true,
        serverName: String = "ci.example.org",
        letsencryptEmail: String = "",
        containerName: String = "test-nginx",
        httpPort: Int = 8080,
        httpsPort: Int = 8443,
        explicitStateDir: Boolean = true,
        serverPort: Int = 18080,
    ): GitTallyConfig =
        GitTallyConfig(
            server =
                ServerConfig(
                    port = serverPort,
                    nginx =
                        NginxConfig(
                            enabled = enabled,
                            serverName = serverName,
                            httpPort = httpPort,
                            httpsPort = httpsPort,
                            containerName = containerName,
                            stateDir = if (explicitStateDir) stateDir.toString() else "",
                            letsencryptEmail = letsencryptEmail,
                        ),
                ),
        )

    private fun expectedRunArgs(): List<String> =
        listOf(
            "docker",
            "run",
            "-d",
            "--name",
            "test-nginx",
            "--publish",
            "8080:80",
            "--publish",
            "8443:443",
            "--network",
            "bridge",
            "--volume",
            "$stateDir/certbot/conf:/etc/letsencrypt",
            "--volume",
            "$stateDir/certbot/www:/var/www/certbot",
            "--volume",
            "$stateDir/nginx/log:/var/log/nginx",
            "--volume",
            "$stateDir/nginx/nginx.conf:/etc/nginx/nginx.conf:ro",
            "--label",
            "org.hoennig.gittally=true",
            "--label",
            "org.hoennig.gittally.repository=${ArtifactKeys.repoKey(repoDir)}",
            "--label",
            "org.hoennig.gittally.role=nginx",
            "nginx",
        )

    private fun expectedCertbotPrefix(): List<String> =
        listOf(
            "docker",
            "run",
            "--rm",
            "--volume",
            "$stateDir/certbot/conf:/etc/letsencrypt",
            "--volume",
            "$stateDir/certbot/www:/var/www/certbot",
            "--volume",
            "$stateDir/certbot/log:/var/log/letsencrypt",
            "certbot/certbot",
        )

    private fun preCreateCertificate() {
        val certFile = stateDir.resolve("certbot/conf/live/ci.example.org/fullchain.pem")
        Files.createDirectories(certFile.parent)
        Files.writeString(certFile, "certificate")
    }

    init {
        beforeEach {
            clearMocks(commandRunner, configLoader)
            captured.clear()
            configsAtContainerRun.clear()
            sleepCount = 0
            repoDir = Files.createTempDirectory("gittally-nginx-repo")
            stateDir = Files.createTempDirectory("gittally-nginx-state")
            every { commandRunner.run(capture(captured), any(), any(), any()) } answers {
                if (captured.last().take(3) == listOf("docker", "run", "-d")) {
                    configsAtContainerRun += Files.readString(stateDir.resolve("nginx/nginx.conf"))
                }
                GitCommandResult(0, "", "")
            }
            manager = NginxProxyManager(commandRunner, configLoader)
            manager.workingDir = repoDir
            manager.sleeper = { sleepCount++ }
        }

        test("start makes no docker calls when disabled") {
            every { configLoader.load(repoDir) } returns nginxConfig(enabled = false)

            manager.start()

            captured.shouldBeEmpty()
        }

        test("resolves the legacy defaults for upstream host, container name, and state dir") {
            every { configLoader.load(repoDir) } returns
                nginxConfig(containerName = "", explicitStateDir = false)

            val settings = manager.resolveSettings().shouldNotBeNull()

            settings.upstreamHost shouldBe "ci.example.org"
            settings.upstreamPort shouldBe 18080
            settings.containerName shouldBe "gittally-nginx-${repoDir.fileName}"
            settings.stateDir.toString() shouldEndWith "gittally/nginx/${ArtifactKeys.repoKey(repoDir)}"
        }

        test("rejects a server name that could inject nginx directives") {
            every { configLoader.load(repoDir) } returns
                nginxConfig(serverName = "evil.example.org;\n} inject {")

            manager.resolveSettings().shouldBeNull()
            manager.start()
            captured.shouldBeEmpty()
        }

        test("rejects a missing server name") {
            every { configLoader.load(repoDir) } returns nginxConfig(serverName = "")

            manager.resolveSettings().shouldBeNull()
        }

        test("rejects a server.port collision with the nginx ports") {
            every { configLoader.load(repoDir) } returns nginxConfig(serverPort = 8080)

            manager.resolveSettings().shouldBeNull()
        }

        test("first start runs the init config, obtains a certificate, and restarts with the full config") {
            every { configLoader.load(repoDir) } returns nginxConfig()

            manager.start()

            // phase 1 runs on the HTTP-only init config, phase 2 on the full HTTPS config
            configsAtContainerRun.size shouldBe 2
            configsAtContainerRun[0] shouldNotContain "listen 443"
            configsAtContainerRun[1] shouldContain "listen 443 ssl;"
            captured shouldContain expectedRunArgs()
            captured shouldContain expectedCertbotPrefix() +
                listOf(
                    "certonly",
                    "--webroot",
                    "--webroot-path",
                    "/var/www/certbot",
                    "--cert-name",
                    "ci.example.org",
                    "-d",
                    "ci.example.org",
                    "--rsa-key-size",
                    "4096",
                    "--non-interactive",
                    "--agree-tos",
                    "--register-unsafely-without-email",
                )
            Files.readString(stateDir.resolve("certbot/conf/options-ssl-nginx.conf")) shouldBe NginxConfigFiles.SSL_OPTIONS
            // the bundled RFC 7919 ffdhe2048 parameters, vendored because certbot removed the download
            Files.readString(stateDir.resolve("certbot/conf/ssl-dhparams.pem")) shouldContain "BEGIN DH PARAMETERS"
        }

        test("start with an existing certificate uses the full config immediately and renews") {
            every { configLoader.load(repoDir) } returns nginxConfig()
            preCreateCertificate()

            manager.start()

            configsAtContainerRun.size shouldBe 2
            configsAtContainerRun[0] shouldContain "listen 443 ssl;"
            captured shouldContain expectedCertbotPrefix() + listOf("renew", "-q")
        }

        test("a configured letsencryptEmail registers with --email instead of unsafely") {
            every { configLoader.load(repoDir) } returns nginxConfig(letsencryptEmail = "admin@example.org")

            val settings = manager.resolveSettings().shouldNotBeNull()
            val args = manager.obtainCertificateArgs(settings)

            args shouldContain "--email"
            args shouldContain "admin@example.org"
            (args.contains("--register-unsafely-without-email")) shouldBe false
        }

        test("stale nginx containers of this repository are removed by label before the start") {
            every { configLoader.load(repoDir) } returns nginxConfig()

            manager.start()

            captured shouldContain
                listOf(
                    "docker",
                    "ps",
                    "-aq",
                    "--filter",
                    "label=org.hoennig.gittally=true",
                    "--filter",
                    "label=org.hoennig.gittally.repository=${ArtifactKeys.repoKey(repoDir)}",
                    "--filter",
                    "label=org.hoennig.gittally.role=nginx",
                )
            captured shouldContain listOf("docker", "rm", "-f", "test-nginx")
        }

        test("does not start while a foreign container occupies an nginx port") {
            every { configLoader.load(repoDir) } returns nginxConfig()
            every {
                commandRunner.run(match { it.take(2) == listOf("docker", "ps") && it.contains("--format") }, any(), any(), any())
            } returns GitCommandResult(0, "abc123\tother-app\t0.0.0.0:8080->80/tcp\tvendor=other", "")

            manager.start()

            captured.none { it.take(3) == listOf("docker", "run", "-d") }.shouldBeTrue()
            sleepCount shouldBe 4
        }

        test("removes a stale gittally-named container occupying an nginx port") {
            every { configLoader.load(repoDir) } returns nginxConfig()
            every {
                commandRunner.run(match { it.take(2) == listOf("docker", "ps") && it.contains("--format") }, any(), any(), any())
            } returnsMany
                listOf(
                    GitCommandResult(0, "abc123\tgittally-nginx-old\t0.0.0.0:8080->80/tcp\t", ""),
                    GitCommandResult(0, "", ""),
                )

            manager.start()

            captured shouldContain listOf("docker", "rm", "-f", "abc123")
            captured shouldContain expectedRunArgs()
        }

        test("renewCertificateAndReload renews the certificate and reloads nginx") {
            every { configLoader.load(repoDir) } returns nginxConfig()
            manager.start()
            captured.clear()

            manager.renewCertificateAndReload()

            captured shouldBe
                listOf(
                    expectedCertbotPrefix() + listOf("renew", "-q"),
                    listOf("docker", "exec", "test-nginx", "nginx", "-s", "reload"),
                )
        }

        test("renewCertificateAndReload is a no-op while no container is managed") {
            manager.renewCertificateAndReload()

            captured.shouldBeEmpty()
        }

        test("stop removes the managed container exactly once") {
            every { configLoader.load(repoDir) } returns nginxConfig()
            manager.start()
            captured.clear()

            manager.stop()
            manager.stop()

            captured shouldBe listOf(listOf("docker", "rm", "-f", "test-nginx"))
        }

        test("a docker failure never throws — the HTTP server keeps running") {
            every { configLoader.load(repoDir) } returns nginxConfig()
            every { commandRunner.run(any(), any(), any(), any()) } throws RuntimeException("docker: command not found")

            manager.start()
        }
    }
}
