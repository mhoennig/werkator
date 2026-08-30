package de.hoennig.werkator.build

import de.hoennig.werkator.config.BranchConfig
import de.hoennig.werkator.config.DockerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Paths

class DispatchingBuildRunnerTest : FunSpec() {
    private val processBuildRunner = mockk<ProcessBuildRunner>()
    private val dockerBuildRunner = mockk<DockerBuildRunner>()
    private val dispatcher = DispatchingBuildRunner(processBuildRunner, dockerBuildRunner)
    private val process = mockk<Process>()
    private val dir = Paths.get(".")

    init {
        beforeEach { clearMocks(processBuildRunner, dockerBuildRunner) }

        test("runs natively by default") {
            val branchConfig = BranchConfig()
            every { processBuildRunner.start("cmd", dir, emptyMap(), dir, branchConfig) } returns process

            dispatcher.start("cmd", dir, emptyMap(), dir, branchConfig) shouldBe process

            verify { dockerBuildRunner wasNot Called }
        }

        test("runs in Docker when the branch enables it") {
            val branchConfig = BranchConfig(docker = DockerConfig(enabled = true, image = "build-env:latest"))
            every { dockerBuildRunner.start("cmd", dir, emptyMap(), dir, branchConfig) } returns process

            dispatcher.start("cmd", dir, emptyMap(), dir, branchConfig) shouldBe process

            verify { processBuildRunner wasNot Called }
        }
    }
}
