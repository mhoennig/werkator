package de.hoennig.gittally.build

import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Starts a single build or clean command and hands the [Process] back to the caller,
 * which owns log streaming and process-tree termination.
 * Native shell execution for now; a Docker runner can plug in later (step 11).
 */
interface BuildRunner {
    fun start(
        command: String,
        workingDir: Path,
        environment: Map<String, String>,
    ): Process
}

@Component
class ProcessBuildRunner : BuildRunner {
    override fun start(
        command: String,
        workingDir: Path,
        environment: Map<String, String>,
    ): Process {
        val processBuilder = ProcessBuilder("bash", "-c", command)
        processBuilder.directory(workingDir.toFile())
        processBuilder.environment().putAll(environment)
        return processBuilder.start()
    }
}
