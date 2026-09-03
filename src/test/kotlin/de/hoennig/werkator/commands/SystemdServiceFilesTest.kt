package de.hoennig.werkator.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Paths

class SystemdServiceFilesTest : FunSpec() {
    init {
        test("unit name is derived from the sanitized repository directory name") {
            SystemdServiceFiles.unitName(Paths.get("/srv/repos/my-repo")) shouldBe "werkator-my-repo.service"
            SystemdServiceFiles.unitName(Paths.get("/srv/repos/my repo!")) shouldBe "werkator-my-repo-.service"
        }

        test("unit file runs the server jar in the repository with restart and environment file") {
            val content =
                SystemdServiceFiles.unitFileContent(
                    repoRoot = Paths.get("/srv/repos/my-repo"),
                    javaExecutable = Paths.get("/usr/lib/jvm/java-21/bin/java"),
                    jarPath = Paths.get("/home/ci/bin/werkator.jar"),
                    envFile = Paths.get("/srv/repos/my-repo/.git/werkator/werkator.env"),
                )

            content shouldContain "Description=Werkator CI for my-repo"
            content shouldContain "After=network-online.target docker.service"
            content shouldContain "WorkingDirectory=/srv/repos/my-repo"
            content shouldContain "EnvironmentFile=-/srv/repos/my-repo/.git/werkator/werkator.env"
            content shouldContain
                """ExecStart="/usr/lib/jvm/java-21/bin/java" ${'$'}JAVA_OPTS -jar "/home/ci/bin/werkator.jar" server"""
            content shouldContain "Restart=always"
            content shouldContain "RestartSec=30"
            content shouldContain "WantedBy=default.target"
        }

        test("resource limits are written when configured and omitted when unset") {
            fun unit(
                memoryMax: String,
                tasksMax: String,
            ) = SystemdServiceFiles.unitFileContent(
                repoRoot = Paths.get("/srv/repos/my-repo"),
                javaExecutable = Paths.get("/usr/bin/java"),
                jarPath = Paths.get("/srv/repos/my-repo/werkator.jar"),
                envFile = Paths.get("/srv/repos/my-repo/werkator.env"),
                memoryMax = memoryMax,
                tasksMax = tasksMax,
            )
            val with = unit(memoryMax = "1G", tasksMax = "512")
            with shouldContain "MemoryMax=1G"
            with shouldContain "TasksMax=512"
            val without = unit(memoryMax = "", tasksMax = "")
            without shouldNotContain "MemoryMax"
            without shouldNotContain "TasksMax"
        }

        test("percent signs in paths are escaped for systemd") {
            val content =
                SystemdServiceFiles.unitFileContent(
                    repoRoot = Paths.get("/srv/100%-repo"),
                    javaExecutable = Paths.get("/usr/bin/java"),
                    jarPath = Paths.get("/srv/100%-repo/werkator.jar"),
                    envFile = Paths.get("/srv/100%-repo/werkator.env"),
                )

            content shouldContain "WorkingDirectory=/srv/100%%-repo"
            content shouldContain "EnvironmentFile=-/srv/100%%-repo/werkator.env"
            content shouldContain """-jar "/srv/100%%-repo/werkator.jar" server"""
        }

        test("prune service cleans containers and images but never volumes") {
            val content = SystemdServiceFiles.pruneServiceContent()

            content shouldContain "Type=oneshot"
            content shouldContain "ExecStart=docker system prune -af"
            // the per-repository Gradle cache volumes must survive the cleanup
            content shouldNotContain "--volumes"
            content shouldContain "ExecCondition=sh -c 'command -v docker'"
        }

        test("prune timer fires nightly at 02:00 and catches up after downtime") {
            val content = SystemdServiceFiles.pruneTimerContent()

            content shouldContain "OnCalendar=*-*-* 02:00:00"
            content shouldContain "Persistent=true"
            content shouldContain "WantedBy=timers.target"
        }

        test("environment file template only tunes the JVM") {
            val content = SystemdServiceFiles.envFileContent()

            content shouldContain "#JAVA_OPTS="
            content shouldContain ".werkator.yml"
        }
        test("the htaccess proxies everything to the configured localhost port") {
            val content = SystemdServiceFiles.htaccessContent(18088)

            content shouldContain "DirectoryIndex disabled"
            content shouldContain "RewriteRule .* http://127.0.0.1:18088%{REQUEST_URI} [proxy]"
        }

        test("the htaccess maps a refused connection to the static maintenance page") {
            val content = SystemdServiceFiles.htaccessContent(18088)

            content shouldContain "ErrorDocument 502 /werkator-maintenance.html"
            content shouldContain "ErrorDocument 503 /werkator-maintenance.html"
            content shouldContain "ErrorDocument 504 /werkator-maintenance.html"
            // the maintenance page itself must not be proxied, or ErrorDocument's sub-request loops
            content shouldContain "RewriteCond %{REQUEST_URI} !^/werkator-maintenance.html$"
        }

        test("the maintenance page is a self-contained, static page naming a retry") {
            val content = SystemdServiceFiles.maintenancePageContent()

            content shouldContain "<html"
            content shouldContain "restarting"
            content shouldContain "retry"
            content shouldNotContain "http://"
            content shouldNotContain "https://"
            content shouldNotContain "src="
        }
    }
}
