package de.hoennig.gittally.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists

class GitAskPassTest : FunSpec() {
    private val runner = GitCommandRunner()
    private val tempDir = Files.createTempDirectory("gittally-askpass-test")

    private fun runScript(
        environment: Map<String, String>,
        prompt: String,
    ): String {
        val script = environment.getValue("GIT_ASKPASS")
        return runner.runOrThrow(listOf("sh", script, prompt), tempDir, environment).stdout.trim()
    }

    init {
        test("script answers username prompts with the account") {
            GitAskPass.withAskPass("builder", "secret-token") { environment ->
                runScript(environment, "Username for 'https://git.example.com':") shouldBe "builder"
            }
        }

        test("script answers password prompts with the token") {
            GitAskPass.withAskPass("builder", "secret-token") { environment ->
                runScript(environment, "Password for 'https://builder@git.example.com':") shouldBe "secret-token"
            }
        }

        test("environment disables terminal prompts") {
            GitAskPass.withAskPass("builder", "secret-token") { environment ->
                environment["GIT_TERMINAL_PROMPT"] shouldBe "0"
            }
        }

        test("script file is only accessible by the owner and contains no secrets") {
            GitAskPass.withAskPass("builder", "secret-token") { environment ->
                val script = Path.of(environment.getValue("GIT_ASKPASS"))
                Files.getPosixFilePermissions(script) shouldBe
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    )
                Files.readString(script).contains("secret-token").shouldBeFalse()
            }
        }

        test("script file is deleted after the block") {
            val script =
                GitAskPass.withAskPass("builder", "secret-token") { environment ->
                    Path.of(environment.getValue("GIT_ASKPASS"))
                }

            script.exists().shouldBeFalse()
        }

        test("script file is deleted when the block throws") {
            var script: Path? = null

            shouldThrow<IllegalStateException> {
                GitAskPass.withAskPass("builder", "secret-token") { environment ->
                    script = Path.of(environment.getValue("GIT_ASKPASS"))
                    error("boom")
                }
            }

            script!!.exists().shouldBeFalse()
        }
    }
}
