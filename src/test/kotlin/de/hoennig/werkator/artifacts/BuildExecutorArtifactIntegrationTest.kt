package de.hoennig.werkator.artifacts

import de.hoennig.werkator.build.BranchWorkspaces
import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.FileBuildResultRepository
import de.hoennig.werkator.build.ProcessBuildRunner
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.gitea.GiteaClient
import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoLinks
import de.hoennig.werkator.repo.RepoRegistry
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.springframework.context.ApplicationEventPublisher
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** Step 05 acceptance: the step 04 executor persists artifacts through the real store. */
class BuildExecutorArtifactIntegrationTest : FunSpec() {
    init {
        test("the executor persists a successful build's logs and reports through the real store") {
            val workingDir = Files.createTempDirectory("werkator-artifact-integration-test")
            val root = workingDir.resolve("artifact-root")
            Files.writeString(
                workingDir.resolve(".werkator.yml"),
                """
                artifacts:
                  rootDir: "$root"
                branches:
                  default:
                    buildCommand: "mkdir -p build/reports && echo report-content > build/reports/index.html && echo built-ok"
                    cleanCommand: ""
                    artifactDirs:
                      - build/reports
                """.trimIndent(),
            )
            val workspace = Files.createDirectories(workingDir.resolve("workspace"))
            val store = FileArtifactStore(ConfigLoader(), workingDir)
            val repo = RepoContext("test", workingDir, FileBuildResultRepository(workingDir.resolve("build-results.json")), store)
            val executor =
                BuildExecutor(
                    configLoader = ConfigLoader(),
                    repoLinks = RepoLinks(mockk<RepoRegistry>().also { every { it.all() } returns listOf(repo) }),
                    giteaClient = mockk<GiteaClient>(relaxed = true),
                    buildRunner = ProcessBuildRunner(),
                    workspaces = BranchWorkspaces { _, _, _ -> workspace },
                    eventPublisher = ApplicationEventPublisher { },
                )

            val build = executor.startBuild(repo, "main", "abc123")

            lateinit var artifactDir: java.nio.file.Path
            eventually(30.seconds) {
                artifactDir = store.artifactDir(build.artifactKey).shouldNotBeNull()
            }
            Files.readString(artifactDir.resolve("build.stdout.log")) shouldContain "built-ok"
            Files.readString(artifactDir.resolve("build.log")) shouldContain "built-ok"
            Files.readString(artifactDir.resolve("reports/index.html")) shouldContain "report-content"
            Files.exists(build.stagingDir) shouldBe false
        }
    }
}
