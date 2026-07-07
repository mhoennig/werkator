package de.hoennig.gittally.git

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
    fun run(
        command: List<String>,
        workingDir: Path,
        environment: Map<String, String> = emptyMap(),
    ): GitCommandResult {
        val builder = ProcessBuilder(command).directory(workingDir.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        // stderr is drained concurrently so neither pipe buffer can block the process
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return GitCommandResult(exitCode, stdout, stderr.get())
    }

    fun runOrThrow(
        command: List<String>,
        workingDir: Path,
        environment: Map<String, String> = emptyMap(),
    ): GitCommandResult {
        val result = run(command, workingDir, environment)
        if (!result.isSuccess) {
            throw GitCommandException(command, result)
        }
        return result
    }
}
