package de.hoennig.gittally.commands

import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.config.GitConfig
import de.hoennig.gittally.config.GitTallyConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk

class ConfigPrintCommandTest : FunSpec() {
    private val yamlWriter = ConfigLoader()
    private val configLoader = mockk<ConfigLoader>()
    private val command = ConfigPrintCommand(configLoader)

    private val rawConfig =
        mapOf<String, Any?>(
            "git" to mapOf("account" to "ci-user", "token" to "s3cr3t-token"),
            "server" to mapOf("port" to 18080),
        )

    init {
        beforeEach {
            clearMocks(configLoader)
            every { configLoader.toYaml(any()) } answers { yamlWriter.toYaml(firstArg()) }
            every { configLoader.loadRaw() } returns rawConfig
            every { configLoader.load() } returns GitTallyConfig(git = GitConfig(account = "ci-user", token = "s3cr3t-token"))
            command.full = false
            command.showSecrets = false
        }

        test("masks the git token by default") {
            val output = captureConsole { command.run() }.stdout

            output shouldNotContain "s3cr3t-token"
            output shouldContain "***"
            output shouldContain "# git.token is masked"
            output shouldContain "account: \"ci-user\""
        }

        test("masks the git token with --full as well") {
            command.full = true

            val output = captureConsole { command.run() }.stdout

            output shouldNotContain "s3cr3t-token"
            output shouldContain "***"
        }

        test("prints the git token with --show-secrets") {
            command.showSecrets = true

            val output = captureConsole { command.run() }.stdout

            output shouldContain "s3cr3t-token"
            output shouldNotContain "masked"
        }

        test("prints the git token with --full --show-secrets") {
            command.full = true
            command.showSecrets = true

            captureConsole { command.run() }.stdout shouldContain "s3cr3t-token"
        }

        test("says nothing about masking when no token is configured") {
            every { configLoader.loadRaw() } returns mapOf("server" to mapOf("port" to 18080))

            captureConsole { command.run() }.stdout shouldNotContain "masked"
        }
    }
}
