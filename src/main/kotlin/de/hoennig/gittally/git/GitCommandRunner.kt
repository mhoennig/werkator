package de.hoennig.werkator.git

import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    fun lines(): List<String> = stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

class GitCommandException(
    command: List<String>,
    result: GitCommandResult,
) : RuntimeException(
        "Command failed with exit code ${result.exitCode}: ${command.joinToString(" ")}\n${result.stderr.trim()}",
    )

@Component
class GitCommandRunner {
    /** [onProcess] receives the started process, so callers can terminate it early (build cancellation). */
    fun run(
        command: List<String>,
        workingDir: Path,
        environment: Map<String, String> = emptyMap(),
        onProcess: (Process) -> Unit = {},
    ): GitCommandResult {
        val builder = ProcessBuilder(command).directory(workingDir.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        onProcess(process)
        // stderr is drained concurrently so neither pipe buffer can block the process;
        // reads tolerate closed streams, e.g. when onProcess terminated the process early
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readSafely() }
        val stdout = process.inputStream.readSafely()
        val exitCode = process.waitFor()
        return GitCommandResult(exitCode, stdout, stderr.get())
    }

    private fun java.io.InputStream.readSafely(): String =
        try {
            bufferedReader().readText()
        } catch (_: java.io.IOException) {
            ""
        }

    fun runOrThrow(
        command: List<String>,
        workingDir: Path,
        environment: Map<String, String> = emptyMap(),
        onProcess: (Process) -> Unit = {},
    ): GitCommandResult {
        val result = run(command, workingDir, environment, onProcess)
        if (!result.isSuccess) {
            throw GitCommandException(command, result)
        }
        return result
    }
}
