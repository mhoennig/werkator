package de.hoennig.gittally.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class ProcessBuildRunnerTest : FunSpec() {
    private val runner = ProcessBuildRunner()

    private fun tempDir(): Path = Files.createTempDirectory("gittally-runner-test")

    init {
        test("propagates the exit code") {
            val process = runner.start("exit 7", tempDir(), emptyMap())

            process.waitFor() shouldBe 7
        }

        test("passes the environment to the command") {
            val process = runner.start("echo value=\$branch", tempDir(), mapOf("branch" to "feature/x"))

            process.inputStream.readAllBytes().decodeToString() shouldContain "value=feature/x"
            process.waitFor() shouldBe 0
        }

        test("runs in the given working directory") {
            val dir = tempDir()

            val process = runner.start("pwd", dir, emptyMap())

            process.inputStream
                .readAllBytes()
                .decodeToString()
                .trim() shouldBe dir.toRealPath().toString()
            process.waitFor() shouldBe 0
        }

        test("keeps stdout and stderr separate") {
            val process = runner.start("echo to-stdout; echo to-stderr 1>&2", tempDir(), emptyMap())

            process.inputStream.readAllBytes().decodeToString() shouldContain "to-stdout"
            process.errorStream.readAllBytes().decodeToString() shouldContain "to-stderr"
            process.waitFor() shouldBe 0
        }
    }
}
