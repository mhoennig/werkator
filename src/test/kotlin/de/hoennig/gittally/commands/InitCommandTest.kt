package de.hoennig.gittally.commands

import de.hoennig.gittally.git.GitService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Paths

class InitCommandTest : FunSpec() {
    private val gitService = mockk<GitService>()
    private val initCommand = InitCommand(gitService)

    init {
        test("creates config files with auto-detected values") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "https://git.example.org/my-org/my-repo.git"

            initCommand.run()

            val projectConfig = tempDir.resolve(".gittally.yml")
            projectConfig.toFile().shouldExist()
            val projectContent = projectConfig.toFile().readText()
            projectContent shouldContain "baseUrl: https://git.example.org"
            projectContent shouldContain "owner: my-org"
            projectContent shouldContain "repo: my-repo"

            val repoConfig = tempDir.resolve(".git/gittally/.gittally.yml")
            repoConfig.toFile().shouldExist()
            val repoContent = repoConfig.toFile().readText()
            repoContent shouldContain "account: \"\"" // no user in https URL
        }

        test("detects account from https url") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "https://ci-user@git.example.org/my-org/my-repo.git"

            initCommand.run()

            val repoConfig = tempDir.resolve(".git/gittally/.gittally.yml")
            val repoContent = repoConfig.toFile().readText()
            repoContent shouldContain "account: \"ci-user\""
        }

        test("parses ssh url") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "git@git.example.org:my-org/my-repo.git"

            initCommand.run()

            val projectConfig = tempDir.resolve(".gittally.yml")
            val projectContent = projectConfig.toFile().readText()
            projectContent shouldContain "baseUrl: https://git.example.org" // fallback to https
            projectContent shouldContain "owner: my-org"
            projectContent shouldContain "repo: my-repo"
        }

        test("does not overwrite existing files") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir

            val projectConfig = tempDir.resolve(".gittally.yml")
            projectConfig.toFile().writeText("existing: content")

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "https://git.example.org/my-org/my-repo.git"

            initCommand.run()

            projectConfig.toFile().readText() shouldBe "existing: content"
        }

        test("--systemd generates unit and environment file with install instructions") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir
            initCommand.systemd = true
            initCommand.jarPathResolver = { Paths.get("/home/ci/bin/gittally.jar") }
            initCommand.javaExecutableResolver = { Paths.get("/usr/bin/java") }

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "https://git.example.org/my-org/my-repo.git"

            initCommand.run()

            val unitName = SystemdServiceFiles.unitName(tempDir)
            val unitFile = tempDir.resolve(".git/gittally/$unitName")
            unitFile.toFile().shouldExist()
            val unitContent = unitFile.toFile().readText()
            unitContent shouldContain "WorkingDirectory=$tempDir"
            unitContent shouldContain """ExecStart="/usr/bin/java" ${'$'}JAVA_OPTS -jar "/home/ci/bin/gittally.jar" server"""

            tempDir.resolve(".git/gittally/gittally.env").toFile().shouldExist()
        }

        test("--systemd also generates the nightly Docker cleanup timer") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir
            initCommand.systemd = true
            initCommand.jarPathResolver = { Paths.get("/home/ci/bin/gittally.jar") }
            initCommand.javaExecutableResolver = { Paths.get("/usr/bin/java") }

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "https://git.example.org/my-org/my-repo.git"

            initCommand.run()

            val pruneService = tempDir.resolve(".git/gittally/gittally-docker-prune.service")
            pruneService.toFile().shouldExist()
            pruneService.toFile().readText() shouldContain "docker system prune -af"
            val pruneTimer = tempDir.resolve(".git/gittally/gittally-docker-prune.timer")
            pruneTimer.toFile().shouldExist()
            pruneTimer.toFile().readText() shouldContain "OnCalendar=*-*-* 02:00:00"
        }

        test("--systemd keeps an existing environment file") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir
            initCommand.systemd = true
            initCommand.jarPathResolver = { Paths.get("/home/ci/bin/gittally.jar") }
            initCommand.javaExecutableResolver = { Paths.get("/usr/bin/java") }

            val envFile = tempDir.resolve(".git/gittally/gittally.env")
            Files.createDirectories(envFile.parent)
            envFile.toFile().writeText("JAVA_OPTS=-Xmx1g\n")

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "https://git.example.org/my-org/my-repo.git"

            initCommand.run()

            envFile.toFile().readText() shouldBe "JAVA_OPTS=-Xmx1g\n"
        }

        test("--systemd without a resolvable jar path generates no unit file") {
            val tempDir = Files.createTempDirectory("gittally-init-test")
            initCommand.workingDir = tempDir
            initCommand.systemd = true
            initCommand.jarPathResolver = { null }

            every { gitService.getTopLevel(tempDir) } returns tempDir
            every { gitService.getOriginUrl(tempDir) } returns "https://git.example.org/my-org/my-repo.git"

            initCommand.run()

            val unitFile = tempDir.resolve(".git/gittally/${SystemdServiceFiles.unitName(tempDir)}")
            unitFile.toFile().exists() shouldBe false
        }

        test("reproduces path root mismatch issue") {
            val tempDir = Files.createTempDirectory("gittally-init-test").toAbsolutePath().normalize()
            initCommand.workingDir = Paths.get(".") // Set to relative path as in real app

            // We need to mock getTopLevel to return the absolute path
            every { gitService.getTopLevel(any()) } returns tempDir
            every { gitService.getOriginUrl(any()) } returns "https://git.example.org/my-org/my-repo.git"

            // This should not throw IllegalArgumentException
            initCommand.run()
        }
    }
}
