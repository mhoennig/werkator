package de.hoennig.werkator.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class GitCommandRunnerTest : FunSpec() {
    private val runner = GitCommandRunner()
    private val tempDir = Files.createTempDirectory("werkator-runner-test")

    init {
        test("captures stdout, stderr and exit code") {
            val result = runner.run(listOf("sh", "-c", "echo out; echo err >&2; exit 3"), tempDir)

            result.exitCode shouldBe 3
            result.stdout.trim() shouldBe "out"
            result.stderr.trim() shouldBe "err"
            result.isSuccess.shouldBeFalse()
        }

        test("runs the command in the given working directory") {
            val result = runner.run(listOf("sh", "-c", "pwd"), tempDir)

            result.isSuccess.shouldBeTrue()
            result.stdout.trim() shouldBe tempDir.toRealPath().toString()
        }

        test("hands the started process to onProcess, so callers can terminate it early") {
            var seen: Process? = null

            val result = runner.run(listOf("sh", "-c", "sleep 30"), tempDir, onProcess = { seen = it.also(Process::destroy) })

            result.isSuccess.shouldBeFalse()
            seen.shouldNotBeNull().isAlive.shouldBeFalse()
        }

        test("passes extra environment variables") {
            val result = runner.run(listOf("sh", "-c", "echo \"\$WERKATOR_TEST_VAR\""), tempDir, mapOf("WERKATOR_TEST_VAR" to "hello"))

            result.stdout.trim() shouldBe "hello"
        }

        test("lines splits stdout and drops blank lines") {
            val result = runner.run(listOf("sh", "-c", "printf 'a\\n\\n b \\n'"), tempDir)

            result.lines() shouldContainExactly listOf("a", "b")
        }

        test("runOrThrow returns the result on success") {
            val result = runner.runOrThrow(listOf("sh", "-c", "echo ok"), tempDir)

            result.stdout.trim() shouldBe "ok"
        }

        test("runOrThrow throws with command and stderr on failure") {
            val exception =
                shouldThrow<GitCommandException> {
                    runner.runOrThrow(listOf("sh", "-c", "echo broken >&2; exit 1"), tempDir)
                }

            exception.message shouldContain "exit code 1"
            exception.message shouldContain "sh -c"
            exception.message shouldContain "broken"
        }
    }
}
