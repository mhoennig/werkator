package de.hoennig.gittally.build

import de.hoennig.gittally.config.BranchConfig
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Starts a single build or clean command and hands the [Process] back to the caller,
 * which owns log streaming and process-tree termination.
 * [repoDir] and [branchConfig] let implementations derive per-repository resources
 * and per-branch settings (step 11: Docker runtime).
 */
interface BuildRunner {
    fun start(
        command: String,
        workingDir: Path,
        environment: Map<String, String>,
        repoDir: Path = workingDir,
        branchConfig: BranchConfig = BranchConfig(),
    ): Process
}

@Component
class ProcessBuildRunner : BuildRunner {
    override fun start(
        command: String,
        workingDir: Path,
        environment: Map<String, String>,
        repoDir: Path,
        branchConfig: BranchConfig,
    ): Process {
        val processBuilder = ProcessBuilder("bash", "-c", command)
        processBuilder.directory(workingDir.toFile())
        processBuilder.environment().putAll(environment)
        return processBuilder.start()
    }
}

/**
 * Selects the runtime per branch: Docker when `branches.<name>.docker.enabled`,
 * native shell execution otherwise (the unchanged default).
 */
@Primary
@Component
class DispatchingBuildRunner(
    private val processBuildRunner: ProcessBuildRunner,
    private val dockerBuildRunner: DockerBuildRunner,
) : BuildRunner {
    override fun start(
        command: String,
        workingDir: Path,
        environment: Map<String, String>,
        repoDir: Path,
        branchConfig: BranchConfig,
    ): Process {
        val runner = if (branchConfig.docker.enabled) dockerBuildRunner else processBuildRunner
        return runner.start(command, workingDir, environment, repoDir, branchConfig)
    }
}
