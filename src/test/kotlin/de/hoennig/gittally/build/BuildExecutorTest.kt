package de.hoennig.gittally.build

import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.gitea.GiteaClient
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class BuildExecutorTest : FunSpec() {
    private class Harness(
        configYaml: String,
        workspaceSubdir: String? = null,
        val buildRunner: BuildRunner = ProcessBuildRunner(),
    ) {
        val workingDir: Path = Files.createTempDirectory("gittally-executor-test")
        val repository = FileBuildResultRepository(workingDir.resolve("build-results.json"))
        val giteaClient = mockk<GiteaClient>(relaxed = true)
        val artifactStore = mockk<ArtifactStore>(relaxed = true)
        val events = CopyOnWriteArrayList<BuildStatusChangedEvent>()
        val workspaceCalls = CopyOnWriteArrayList<Pair<String, String>>()
        val workspaces =
            BranchWorkspaces { branch, commit, _ ->
                workspaceCalls += branch to commit
                if (workspaceSubdir == null) {
                    workingDir
                } else {
                    Files.createDirectories(workingDir.resolve(workspaceSubdir))
                }
            }
        val executor =
            BuildExecutor(
                repository = repository,
                configLoader = ConfigLoader(),
                giteaClient = giteaClient,
                buildRunner = buildRunner,
                workspaces = workspaces,
                artifactStore = artifactStore,
                eventPublisher =
                    ApplicationEventPublisher { event ->
                        if (event is BuildStatusChangedEvent) {
                            events += event
                        }
                    },
            )

        init {
            Files.writeString(workingDir.resolve(".gittally.yml"), configYaml)
        }
    }

    private fun harness(
        buildCommand: String,
        cleanCommand: String = "",
        maxConcurrent: Int = 1,
        workspaceSubdir: String? = null,
        buildRunner: BuildRunner? = null,
    ) = Harness(
        """
        builds:
          maxConcurrent: $maxConcurrent
        branches:
          default:
            buildCommand: "$buildCommand"
            cleanCommand: "$cleanCommand"
        """.trimIndent(),
        workspaceSubdir = workspaceSubdir,
        buildRunner = buildRunner ?: ProcessBuildRunner(),
    )

    private suspend fun awaitStatus(
        harness: Harness,
        branch: String,
        status: BuildStatus,
    ) {
        eventually(30.seconds) {
            harness.repository.latestFor(branch)?.status shouldBe status
        }
    }

    private suspend fun awaitIdle(harness: Harness) {
        eventually(30.seconds) {
            harness.executor.currentBuilds().shouldBeEmpty()
        }
    }

    init {
        test("a successful build transitions pending, running, success and captures all logs") {
            val h =
                harness(
                    buildCommand = "echo out-\$branch; echo err-\$branch 1>&2",
                    cleanCommand = "echo clean-\$branch",
                )

            val build = h.executor.startBuild("main", "abc123", h.workingDir)

            awaitStatus(h, "main", BuildStatus.SUCCESS)
            awaitIdle(h)
            val result = h.repository.latestFor("main").shouldNotBeNull()
            result.artifactKey shouldBe build.artifactKey
            result.duration shouldNotBe null
            h.events.map { it.result.status } shouldContainExactly
                listOf(BuildStatus.PENDING, BuildStatus.RUNNING, BuildStatus.SUCCESS)
            h.workspaceCalls shouldContain ("main" to "abc123")

            val stdoutLog = Files.readString(build.stagingDir.resolve("build.stdout.log"))
            stdoutLog shouldContain "clean-main"
            stdoutLog shouldContain "out-main"
            Files.readString(build.stagingDir.resolve("build.stderr.log")) shouldContain "err-main"
            val liveLog = Files.readString(build.liveLogFile)
            liveLog shouldContain "clean-main"
            liveLog shouldContain "out-main"
            liveLog shouldContain "err-main"

            verify { h.giteaClient.publishStatus("abc123", BuildStatus.PENDING, any(), null, h.workingDir) }
            verify { h.giteaClient.publishStatus("abc123", BuildStatus.RUNNING, any(), null, h.workingDir) }
            verify { h.giteaClient.publishStatus("abc123", BuildStatus.SUCCESS, any(), null, h.workingDir) }
            verify { h.artifactStore.persist(match { it.status == BuildStatus.SUCCESS }, build.stagingDir, h.workingDir) }
        }

        test("build commands run in the workspace prepared for the branch") {
            val h = harness(buildCommand = "pwd", workspaceSubdir = "branch-workspace")

            val build = h.executor.startBuild("main", "abc123", h.workingDir)

            awaitStatus(h, "main", BuildStatus.SUCCESS)
            awaitIdle(h)
            Files.readString(build.stagingDir.resolve("build.stdout.log")) shouldContain "branch-workspace"
        }

        test("the repository reports RUNNING while the build sleeps") {
            val h = harness("sleep 10")

            val build = h.executor.startBuild("main", "abc123", h.workingDir)

            eventually(10.seconds) {
                h.repository.latestFor("main")?.status shouldBe BuildStatus.RUNNING
            }
            h.executor.currentBuilds().map { it.artifactKey } shouldContainExactly listOf(build.artifactKey)

            h.executor.cancel(build.artifactKey).shouldBeTrue()
            awaitStatus(h, "main", BuildStatus.CANCELLED)
        }

        test("the duration measures build time only, not the queue wait") {
            // the first build sleeps, the second (queued behind it) finishes instantly
            val h = harness("test -f slow-done || { touch slow-done; sleep 2; }")

            h.executor.startBuild("main", "abc123", h.workingDir)
            val second = h.executor.startBuild("main", "abc124", h.workingDir)

            eventually(30.seconds) {
                h.repository
                    .history()
                    .first { it.artifactKey == second.artifactKey }
                    .status shouldBe BuildStatus.SUCCESS
            }
            val result = h.repository.history().first { it.artifactKey == second.artifactKey }
            val runningSince = result.runningSince.shouldNotBeNull()
            val waitMillis =
                java.time.Duration
                    .between(result.startedAt, runningSince)
                    .toMillis()
            waitMillis shouldBeGreaterThan 1000L
            result.duration.shouldNotBeNull().toMillis() shouldBeLessThan waitMillis
        }

        test("cancel kills a long auxiliary preparation phase instead of waiting for it") {
            // like DockerBuildRunner's synchronous image build: a slow aux process runs
            // before the build command and fails the build when it was terminated
            val auxRunner =
                object : BuildRunner {
                    override fun start(
                        command: String,
                        workingDir: java.nio.file.Path,
                        environment: Map<String, String>,
                        repoDir: java.nio.file.Path,
                        branchConfig: de.hoennig.gittally.config.BranchConfig,
                        onAuxProcess: (Process) -> Unit,
                    ): Process {
                        val aux = ProcessBuilder("bash", "-c", "sleep 60").directory(workingDir.toFile()).start()
                        onAuxProcess(aux)
                        aux.waitFor()
                        check(aux.exitValue() == 0) { "aux phase failed" }
                        return ProcessBuilder("bash", "-c", "echo built").directory(workingDir.toFile()).start()
                    }
                }
            val h = harness("unused", buildRunner = auxRunner)

            val build = h.executor.startBuild("main", "abc123", h.workingDir)
            eventually(10.seconds) {
                h.repository.latestFor("main")?.status shouldBe BuildStatus.RUNNING
            }
            h.executor.cancel(build.artifactKey).shouldBeTrue()

            // must beat the 60s aux sleep by a wide margin — the aux process gets killed
            eventually(15.seconds) {
                h.repository.latestFor("main")?.status shouldBe BuildStatus.CANCELLED
            }
        }

        test("a build command override replaces the branch's build command and is recorded in the result") {
            val h = harness(buildCommand = "echo regular-\$branch")

            val nightly = h.executor.startBuild("main", "sha-1", h.workingDir, "echo nightly-\$branch")
            awaitStatus(h, "main", BuildStatus.SUCCESS)
            awaitIdle(h)

            val stdoutLog = Files.readString(nightly.stagingDir.resolve("build.stdout.log"))
            stdoutLog shouldContain "nightly-main"
            stdoutLog shouldNotContain "regular-main"
            Files.readString(nightly.liveLogFile) shouldContain "triggered by: auto-build slot"
            h.repository
                .latestFor("main")
                .shouldNotBeNull()
                .buildCommandOverride shouldBe "echo nightly-\$branch"

            // the same branch without an override runs the regular command
            val regular = h.executor.startBuild("main", "sha-2", h.workingDir)
            awaitStatus(h, "main", BuildStatus.SUCCESS)
            awaitIdle(h)
            Files.readString(regular.stagingDir.resolve("build.stdout.log")) shouldContain "regular-main"
            h.repository
                .latestFor("main")
                .shouldNotBeNull()
                .buildCommandOverride shouldBe null
        }

        test("a named build is recorded under its name, keyed by the sanitized name") {
            val h = harness(buildCommand = "echo regular-\$branch")

            val build = h.executor.startBuild("main", "sha-1", h.workingDir, "echo nightly-\$branch", "main@nightly")
            awaitStatus(h, "main@nightly", BuildStatus.SUCCESS)
            awaitIdle(h)

            val result = h.repository.latestFor("main@nightly").shouldNotBeNull()
            result.branch shouldBe "main"
            result.name shouldBe "main@nightly"
            result.artifactKey shouldBe build.artifactKey
            build.artifactKey shouldContain "main_nightly"
            Files.readString(build.liveLogFile) shouldContain "build name: main@nightly"
            // the branch's own pool stays empty — the named build does not shadow it
            h.repository.latestFor("main") shouldBe null
        }

        test("startBuild returns the active build of the same branch and commit instead of stacking a duplicate") {
            val h = harness("sleep 30")

            val first = h.executor.startBuild("main", "abc123", h.workingDir)
            // a double-triggered UI restart: same branch, same commit, while queued or running
            val duplicate = h.executor.startBuild("main", "abc123", h.workingDir)
            duplicate.artifactKey shouldBe first.artifactKey
            h.repository.history().map { it.artifactKey } shouldContainExactly listOf(first.artifactKey)

            // a build of the same commit with a command override runs a different command — not a duplicate
            val nightly = h.executor.startBuild("main", "abc123", h.workingDir, "echo full-check")
            nightly.artifactKey shouldNotBe first.artifactKey

            // another commit of the branch is a distinct build, queued behind the first
            val newerCommit = h.executor.startBuild("main", "abc124", h.workingDir)
            newerCommit.artifactKey shouldNotBe first.artifactKey

            // a cancel-requested build no longer blocks re-queueing its commit
            h.executor.cancel(first.artifactKey).shouldBeTrue()
            val again = h.executor.startBuild("main", "abc123", h.workingDir)
            again.artifactKey shouldNotBe first.artifactKey

            h.executor.cancel(nightly.artifactKey).shouldBeTrue()
            h.executor.cancel(newerCommit.artifactKey).shouldBeTrue()
            h.executor.cancel(again.artifactKey).shouldBeTrue()
            eventually(30.seconds) {
                h.executor.currentBuilds().shouldBeEmpty()
            }
        }

        test("a build cancelled while still queued records neither runningSince nor a duration") {
            val h = harness("sleep 30")

            val first = h.executor.startBuild("main", "abc123", h.workingDir)
            val second = h.executor.startBuild("main", "abc124", h.workingDir)
            eventually(30.seconds) {
                h.executor.currentBuilds().map { it.artifactKey } shouldContain first.artifactKey
            }
            h.executor.cancel(second.artifactKey).shouldBeTrue()
            h.executor.cancel(first.artifactKey).shouldBeTrue()

            eventually(30.seconds) {
                h.repository
                    .history()
                    .first { it.artifactKey == second.artifactKey }
                    .status shouldBe BuildStatus.CANCELLED
            }
            val cancelled = h.repository.history().first { it.artifactKey == second.artifactKey }
            cancelled.runningSince shouldBe null
            cancelled.duration shouldBe null
        }

        test("a failing build command records FAILED with a duration") {
            val h = harness("exit 3")

            h.executor.startBuild("main", "abc123", h.workingDir)

            awaitStatus(h, "main", BuildStatus.FAILED)
            awaitIdle(h)
            h.repository.latestFor("main")?.duration shouldNotBe null
            h.events.map { it.result.status } shouldContainExactly
                listOf(BuildStatus.PENDING, BuildStatus.RUNNING, BuildStatus.FAILED)
        }

        test("a failing clean command fails the build without running the build command") {
            val h = harness(buildCommand = "echo forbidden-\$branch", cleanCommand = "exit 1")

            val build = h.executor.startBuild("main", "abc123", h.workingDir)

            awaitStatus(h, "main", BuildStatus.FAILED)
            awaitIdle(h)
            Files.readString(build.stagingDir.resolve("build.stdout.log")) shouldNotContain "forbidden-main"
            Files.readString(build.liveLogFile) shouldNotContain "forbidden-main"
        }

        test("cancel kills a sleeping process tree and records CANCELLED") {
            val h = harness("echo \$\$ > pid-file; sleep 30 & sleep 30 & wait")

            val build = h.executor.startBuild("main", "abc123", h.workingDir)

            lateinit var root: ProcessHandle
            var children = emptyList<ProcessHandle>()
            eventually(10.seconds) {
                val pid =
                    Files
                        .readString(h.workingDir.resolve("pid-file"))
                        .trim()
                        .toLong()
                root = ProcessHandle.of(pid).orElseThrow()
                children = root.descendants().toList()
                children.size shouldBe 2
            }

            h.executor.cancel(build.artifactKey).shouldBeTrue()

            awaitStatus(h, "main", BuildStatus.CANCELLED)
            h.repository.latestFor("main")?.duration shouldNotBe null
            eventually(10.seconds) {
                root.isAlive shouldBe false
                children.forEach { it.isAlive shouldBe false }
            }
        }

        test("shutdown kills an executing build and records INTERRUPTED, not FAILED") {
            val h = harness("echo \$\$ > pid-file; sleep 30")

            val build = h.executor.startBuild("main", "abc123", h.workingDir)
            eventually(10.seconds) {
                Files.exists(h.workingDir.resolve("pid-file")).shouldBeTrue()
            }
            val pid =
                Files
                    .readString(h.workingDir.resolve("pid-file"))
                    .trim()
                    .toLong()

            h.executor.shutdown()

            // shutdown() drains synchronously, so the result is already persisted
            h.repository.latestFor("main")?.status shouldBe BuildStatus.INTERRUPTED
            h.executor.currentBuilds().shouldBeEmpty()
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
            h.events.map { it.result.status } shouldContainExactly
                listOf(BuildStatus.PENDING, BuildStatus.RUNNING, BuildStatus.INTERRUPTED)
            Files.readString(build.liveLogFile) shouldContain "build interrupted by shutdown"
            verify { h.giteaClient.publishStatus("abc123", BuildStatus.INTERRUPTED, any(), null, h.workingDir) }
            verify { h.artifactStore.persist(match { it.status == BuildStatus.INTERRUPTED }, build.stagingDir, any()) }
        }

        test("a build still queued at shutdown stays PENDING for the startup recovery") {
            val h = harness("sleep 30")

            val first = h.executor.startBuild("main", "sha-1", h.workingDir)
            val second = h.executor.startBuild("main", "sha-2", h.workingDir)
            eventually(10.seconds) {
                h.repository
                    .history()
                    .first { it.artifactKey == first.artifactKey }
                    .status shouldBe BuildStatus.RUNNING
            }

            h.executor.shutdown()

            h.repository
                .history()
                .first { it.artifactKey == first.artifactKey }
                .status shouldBe BuildStatus.INTERRUPTED
            // the branch worker drops the queued build without a transition or a process
            awaitIdle(h)
            h.repository
                .history()
                .first { it.artifactKey == second.artifactKey }
                .status shouldBe BuildStatus.PENDING
            h.events
                .filter { it.result.artifactKey == second.artifactKey }
                .map { it.result.status } shouldContainExactly listOf(BuildStatus.PENDING)
            verify(exactly = 0) { h.giteaClient.publishStatus("sha-2", BuildStatus.RUNNING, any(), any(), any()) }
        }

        test("shutdown without any build in flight is a no-op") {
            val h = harness("echo ok")

            h.executor.startBuild("main", "abc123", h.workingDir)
            awaitStatus(h, "main", BuildStatus.SUCCESS)
            awaitIdle(h)

            h.executor.shutdown()

            h.repository.latestFor("main")?.status shouldBe BuildStatus.SUCCESS
        }

        test("cancel with an unknown artifact key returns false") {
            val h = harness("echo ok")

            h.executor.cancel("unknown-key").shouldBeFalse()
        }

        test("the live log grows while the build is still running") {
            val h = harness("echo one-\$branch; sleep 3; echo two-\$branch")

            val build = h.executor.startBuild("main", "abc123", h.workingDir)

            eventually(10.seconds) {
                Files.readString(build.liveLogFile) shouldContain "one-main"
            }
            h.repository.latestFor("main")?.status shouldBe BuildStatus.RUNNING

            awaitStatus(h, "main", BuildStatus.SUCCESS)
            Files.readString(build.liveLogFile) shouldContain "two-main"
        }

        test("a Gitea failure does not fail the build") {
            val h = harness("echo ok")
            every {
                h.giteaClient.publishStatus(any(), any(), any(), any(), any())
            } throws RuntimeException("gitea down")

            h.executor.startBuild("main", "abc123", h.workingDir)

            awaitStatus(h, "main", BuildStatus.SUCCESS)
        }

        test("with maxConcurrent 1 a second branch stays PENDING until the first finished") {
            val h =
                Harness(
                    """
                    builds:
                      maxConcurrent: 1
                    branches:
                      branch-a:
                        buildCommand: "sleep 1"
                        cleanCommand: ""
                      branch-b:
                        buildCommand: "echo ok"
                        cleanCommand: ""
                    """.trimIndent(),
                )

            h.executor.startBuild("branch-a", "sha-a", h.workingDir)
            h.executor.startBuild("branch-b", "sha-b", h.workingDir)

            h.repository.latestFor("branch-b")?.status shouldBe BuildStatus.PENDING

            awaitStatus(h, "branch-b", BuildStatus.SUCCESS)
            awaitStatus(h, "branch-a", BuildStatus.SUCCESS)
            val transitions = h.events.map { it.result.branch to it.result.status }
            transitions.indexOf("branch-b" to BuildStatus.RUNNING) shouldBeGreaterThan
                transitions.indexOf("branch-a" to BuildStatus.SUCCESS)
        }

        test("with maxConcurrent 2 two branches build at the same time") {
            val h = harness("sleep 10", maxConcurrent = 2)

            val buildA = h.executor.startBuild("branch-a", "sha-a", h.workingDir)
            val buildB = h.executor.startBuild("branch-b", "sha-b", h.workingDir)

            eventually(10.seconds) {
                h.repository.latestFor("branch-a")?.status shouldBe BuildStatus.RUNNING
                h.repository.latestFor("branch-b")?.status shouldBe BuildStatus.RUNNING
            }
            h.executor.currentBuilds().map { it.artifactKey } shouldContainExactlyInAnyOrder
                listOf(buildA.artifactKey, buildB.artifactKey)

            h.executor.cancel(buildA.artifactKey).shouldBeTrue()
            h.executor.cancel(buildB.artifactKey).shouldBeTrue()
            awaitStatus(h, "branch-a", BuildStatus.CANCELLED)
            awaitStatus(h, "branch-b", BuildStatus.CANCELLED)
        }

        test("a second build of the same branch waits even when a slot is free") {
            val h = harness("sleep 1", maxConcurrent = 2)

            val first = h.executor.startBuild("main", "sha-1", h.workingDir)
            val second = h.executor.startBuild("main", "sha-2", h.workingDir)

            eventually(30.seconds) {
                h.repository
                    .history()
                    .map { it.status }
                    .toSet() shouldBe setOf(BuildStatus.SUCCESS)
            }
            val transitions = h.events.map { it.result.artifactKey to it.result.status }
            transitions.indexOf(second.artifactKey to BuildStatus.RUNNING) shouldBeGreaterThan
                transitions.indexOf(first.artifactKey to BuildStatus.SUCCESS)
        }

        test("cancel only affects the addressed build, other branches keep running") {
            val h = harness("sleep 10", maxConcurrent = 2)

            val buildA = h.executor.startBuild("branch-a", "sha-a", h.workingDir)
            val buildB = h.executor.startBuild("branch-b", "sha-b", h.workingDir)
            eventually(10.seconds) {
                h.repository.latestFor("branch-a")?.status shouldBe BuildStatus.RUNNING
                h.repository.latestFor("branch-b")?.status shouldBe BuildStatus.RUNNING
            }

            h.executor.cancel(buildA.artifactKey).shouldBeTrue()

            awaitStatus(h, "branch-a", BuildStatus.CANCELLED)
            h.repository.latestFor("branch-b")?.status shouldBe BuildStatus.RUNNING
            h.executor.currentBuilds().map { it.artifactKey } shouldContainExactly listOf(buildB.artifactKey)

            h.executor.cancel(buildB.artifactKey).shouldBeTrue()
            awaitStatus(h, "branch-b", BuildStatus.CANCELLED)
        }
    }
}
