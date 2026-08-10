package de.hoennig.gittally.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Paths

class SystemdServiceFilesTest : FunSpec() {
    init {
        test("unit name is derived from the sanitized repository directory name") {
            SystemdServiceFiles.unitName(Paths.get("/srv/repos/my-repo")) shouldBe "gittally-my-repo.service"
            SystemdServiceFiles.unitName(Paths.get("/srv/repos/my repo!")) shouldBe "gittally-my-repo-.service"
        }

        test("unit file runs the server jar in the repository with restart and environment file") {
            val content =
                SystemdServiceFiles.unitFileContent(
                    repoRoot = Paths.get("/srv/repos/my-repo"),
                    javaExecutable = Paths.get("/usr/lib/jvm/java-21/bin/java"),
                    jarPath = Paths.get("/home/ci/bin/gittally.jar"),
                    envFile = Paths.get("/srv/repos/my-repo/.git/gittally/gittally.env"),
                )

            content shouldContain "Description=GitTally CI for my-repo"
            content shouldContain "After=network-online.target docker.service"
            content shouldContain "WorkingDirectory=/srv/repos/my-repo"
            content shouldContain "EnvironmentFile=-/srv/repos/my-repo/.git/gittally/gittally.env"
            content shouldContain
                """ExecStart="/usr/lib/jvm/java-21/bin/java" ${'$'}JAVA_OPTS -jar "/home/ci/bin/gittally.jar" server"""
            content shouldContain "Restart=always"
            content shouldContain "RestartSec=30"
            content shouldContain "WantedBy=default.target"
        }

        test("percent signs in paths are escaped for systemd") {
            val content =
                SystemdServiceFiles.unitFileContent(
                    repoRoot = Paths.get("/srv/100%-repo"),
                    javaExecutable = Paths.get("/usr/bin/java"),
                    jarPath = Paths.get("/srv/100%-repo/gittally.jar"),
                    envFile = Paths.get("/srv/100%-repo/gittally.env"),
                )

            content shouldContain "WorkingDirectory=/srv/100%%-repo"
            content shouldContain "EnvironmentFile=-/srv/100%%-repo/gittally.env"
            content shouldContain """-jar "/srv/100%%-repo/gittally.jar" server"""
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
            content shouldContain ".gittally.yml"
        }
    }
}
