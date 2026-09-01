package de.hoennig.werkator.commands

import de.hoennig.werkator.git.GitService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files

class ControlTokenCommandTest : FunSpec() {
    private val gitService = mockk<GitService>()
    private val command = ControlTokenCommand(gitService)

    init {
        test("creates the token like the server would and prints the same one on a re-run") {
            val tempDir = Files.createTempDirectory("werkator-token-test")
            command.workingDir = tempDir
            every { gitService.getTopLevel(any()) } returns tempDir

            command.call() shouldBe 0

            val tokenFile = tempDir.resolve(".git/werkator/control-token")
            val token = tokenFile.toFile().readText().trim()
            token shouldMatch Regex("[0-9a-f]{48}")

            command.call() shouldBe 0
            tokenFile.toFile().readText().trim() shouldBe token
        }

        test("fails with exit code 2 outside a repository") {
            every { gitService.getTopLevel(any()) } throws IllegalStateException("not a git repository")

            command.call() shouldBe 2
        }
    }
}
