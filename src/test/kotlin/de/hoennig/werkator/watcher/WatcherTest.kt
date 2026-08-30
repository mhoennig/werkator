package de.hoennig.werkator.watcher

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.hoennig.werkator.build.ArtifactKeys
import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.build.FileBuildResultRepository
import de.hoennig.werkator.build.GitWorktreeWorkspaces
import de.hoennig.werkator.build.RunningBuild
import de.hoennig.werkator.config.ArtifactsConfig
import de.hoennig.werkator.config.AutoBuildConfig
import de.hoennig.werkator.config.BranchConfig
import de.hoennig.werkator.config.BuildDefinition
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.config.TriggerConfig
import de.hoennig.werkator.config.WatcherConfig
import de.hoennig.werkator.config.WerkatorConfig
import de.hoennig.werkator.git.GitService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WatcherTest : FunSpec() {
    private val noon = Instant.parse("2026-07-07T12:00:00Z")

    private inner class Harness(
        config: WerkatorConfig = WerkatorConfig(),
    ) {
        val workingDir: Path = Files.createTempDirectory("werkator-watcher-test")
        val repository = FileBuildResultRepository(workingDir.resolve(".git/werkator/build-results.json"))
        val gitService = mockk<GitService>()
        val buildExecutor = mockk<BuildExecutor>()
        val artifactStore = mockk<ArtifactStore>()
        val startedBuilds = CopyOnWriteArrayList<Pair<String, String>>()
        val configLoader = mockk<ConfigLoader>()
        val watcher =
            Watcher(
                gitService = gitService,
                buildExecutor = buildExecutor,
                repository = repository,
                artifactStore = artifactStore,
                configLoader = configLoader,
                clock = Clock.fixed(noon, ZoneOffset.UTC),
            )

        private var seedCounter = 0L

        init {
            every { configLoader.load(any()) } returns config
            every { gitService.fetchOrigin(any()) } returns Unit
            every { gitService.localBranches(any()) } returns emptyList()
            every { gitService.originBranches(any()) } returns emptyList()
            every { gitService.newOriginBranches(any(), any()) } returns emptyList()
            every { gitService.hasNewCommits(any(), any()) } returns false
            every { gitService.originHeadCommit(any(), any()) } returns null
            every { gitService.originBranchCommitTimes(any()) } returns emptyMap()
            every { gitService.originBranchHeads(any()) } returns emptyMap()
            every { gitService.showFileAtCommit(any(), any(), any()) } returns null
            every { configLoader.loadWithBranchLayer(any(), anyNullable()) } returns config
            every { gitService.pullRequestHeads(any()) } returns emptySet()
            every { gitService.worktreePrune(any()) } returns Unit
            every { gitService.fastForwardLocalBranches(any()) } returns emptyList()
            every { buildExecutor.currentBuilds() } returns emptyList()
            every { buildExecutor.startBuild(any(), any(), any(), any()) } answers {
                val branch = firstArg<String>()
                val commit = secondArg<String>()
                startedBuilds += branch to commit
                runningBuild(branch, commit)
            }
            every { artifactStore.prune(any()) } returns emptyList()
        }

        /** Appends a result; later seeds get later start timestamps. */
        fun seed(
            branch: String,
            status: BuildStatus,
            commit: String = "commit-0",
            build: String = BuildDefinition.DEFAULT,
        ): BuildResult {
            val startedAt = noon.minusSeconds(3600).plusSeconds(seedCounter++)
            val result =
                BuildResult(
                    branch = branch,
                    build = build,
                    commit = commit,
                    status = status,
                    startedAt = startedAt,
                    artifactKey = ArtifactKeys.buildKey(BuildDefinition.poolName(branch, build), startedAt),
                )
            repository.append(result)
            return result
        }

        fun worktreeDir(branch: String): Path {
            val dir = workingDir.resolve(GitWorktreeWorkspaces.WORKTREES_DIR).resolve(ArtifactKeys.branchKey(branch))
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("marker.txt"), branch)
            return dir
        }

        fun autoBuildState() = FileAutoBuildState(workingDir.resolve(Watcher.AUTO_BUILDS_FILE))
    }

    private fun runningBuild(
        branch: String,
        commit: String,
    ): RunningBuild {
        val stagingDir = Files.createTempDirectory("werkator-watcher-staging")
        return RunningBuild(
            branch = branch,
            commit = commit,
            artifactKey = ArtifactKeys.buildKey(branch, Instant.now()),
            startedAt = Instant.now(),
            stagingDir = stagingDir,
            liveLogFile = stagingDir.resolve("build.log"),
        )
    }

    private fun autoBuildConfig(vararg times: String): WerkatorConfig =
        WerkatorConfig(
            branches =
                mapOf(
                    "default" to BranchConfig(),
                    "main" to BranchConfig(autoBuild = AutoBuildConfig(enabled = true, times = times.toList())),
                ),
        )

    init {
        test("a fetch failure is exposed in the state and only retried next cycle") {
            val harness = Harness()
            every { harness.gitService.fetchOrigin(any()) } throws RuntimeException("origin unreachable")

            harness.watcher.poll(harness.workingDir)

            harness.watcher
                .state()
                .lastFetchError
                .shouldNotBeNull() shouldContain "origin unreachable"
            harness.watcher.state().lastPollAt shouldBe noon
            harness.startedBuilds.shouldBeEmpty()
            verify(exactly = 0) { harness.artifactStore.prune(any()) }

            every { harness.gitService.fetchOrigin(any()) } returns Unit
            harness.watcher.poll(harness.workingDir)

            harness.watcher
                .state()
                .lastFetchError
                .shouldBeNull()
        }

        test("a lasting fetch failure is logged once, and so is the recovery") {
            val harness = Harness()
            val logged = captureWatcherLog()
            every { harness.gitService.fetchOrigin(any()) } throws RuntimeException("origin unreachable")

            repeat(5) { harness.watcher.poll(harness.workingDir) }

            // one wrong token used to write a warning every ten seconds, 297 of them in an hour
            logged().filter { it.contains("fetching origin failed") } shouldHaveSize 1

            every { harness.gitService.fetchOrigin(any()) } returns Unit
            repeat(3) { harness.watcher.poll(harness.workingDir) }

            logged().filter { it.contains("fetching origin succeeded again") } shouldHaveSize 1

            // a different failure is a different message and is worth saying again
            every { harness.gitService.fetchOrigin(any()) } throws RuntimeException("host is down")
            harness.watcher.poll(harness.workingDir)

            logged().filter { it.contains("fetching origin failed") } shouldHaveSize 2
        }

        test("poll enqueues changed local branches before recent new origin branches") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("main", "feature/new")
            every { harness.gitService.localBranches(any()) } returns listOf("main", "untracked-local")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.newOriginBranches(any(), any()) } returns listOf("feature/new")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"
            every { harness.gitService.originHeadCommit("feature/new", any()) } returns "commit-feature"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly
                listOf("main" to "commit-main", "feature/new" to "commit-feature")
        }

        test("poll fast-forwards local branch refs only after the enqueue decision was made") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"
            every { harness.gitService.fastForwardLocalBranches(any()) } returns listOf("main")

            harness.watcher.poll(harness.workingDir)

            // syncing the ref before the decision would hide the very commit being enqueued here
            harness.startedBuilds shouldContainExactly listOf("main" to "commit-main")
            verifyOrder {
                harness.gitService.hasNewCommits("main", any())
                harness.gitService.fastForwardLocalBranches(any())
            }
        }

        test("a failing fast-forward does not abort the poll cycle") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.fastForwardLocalBranches(any()) } throws RuntimeException("ref locked")

            harness.watcher.poll(harness.workingDir)

            harness.watcher
                .state()
                .lastPollError
                .shouldBeNull()
            verify { harness.artifactStore.prune(any()) }
        }

        test("poll leaves local branch refs alone when fastForwardLocalRefs is disabled") {
            val harness = Harness(WerkatorConfig(watcher = WatcherConfig(fastForwardLocalRefs = false)))
            every { harness.gitService.originBranches(any()) } returns listOf("main")

            harness.watcher.poll(harness.workingDir)

            verify(exactly = 0) { harness.gitService.fastForwardLocalBranches(any()) }
        }

        test("poll skips a branch whose build is already pending or running") {
            val harness = Harness()
            harness.seed("main", BuildStatus.PENDING, commit = "commit-old")
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-new"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds.shouldBeEmpty()
        }

        test("a poll cycle completes while a build is running and still enqueues other branches") {
            val harness = Harness()
            harness.seed("main", BuildStatus.RUNNING, commit = "commit-1")
            every { harness.buildExecutor.currentBuilds() } returns listOf(runningBuild("main", "commit-1"))
            every { harness.gitService.originBranches(any()) } returns listOf("main", "feature/other")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.newOriginBranches(any(), any()) } returns listOf("feature/other")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-2"
            every { harness.gitService.originHeadCommit("feature/other", any()) } returns "commit-3"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("feature/other" to "commit-3")
            harness.watcher.state().queuedBranches shouldContainExactly listOf("main")
        }

        test("poll does not re-enqueue a commit that was already built") {
            val harness = Harness()
            harness.seed("main", BuildStatus.SUCCESS, commit = "commit-abc")
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-abc"

            harness.watcher.poll(harness.workingDir)
            harness.startedBuilds.shouldBeEmpty()

            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-def"
            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("main" to "commit-def")
        }

        test("poll filters new origin branches by the configured newBranchMaxAge") {
            val harness = Harness(WerkatorConfig(watcher = WatcherConfig(newBranchMaxAge = "12h")))

            harness.watcher.poll(harness.workingDir)

            verify { harness.gitService.newOriginBranches(Duration.ofHours(12), any()) }
        }

        test("a branch requiring a pull request is only built when its head matches a pull-request head") {
            val harness = Harness(WerkatorConfig(branches = mapOf("default" to BranchConfig(requirePullRequest = true))))
            every { harness.gitService.originBranches(any()) } returns listOf("feature/pr", "feature/no-pr")
            every { harness.gitService.newOriginBranches(any(), any()) } returns listOf("feature/pr", "feature/no-pr")
            every { harness.gitService.originHeadCommit("feature/pr", any()) } returns "commit-pr"
            every { harness.gitService.originHeadCommit("feature/no-pr", any()) } returns "commit-solo"
            every { harness.gitService.pullRequestHeads(any()) } returns setOf("commit-pr")

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("feature/pr" to "commit-pr")
        }

        test("pull-request refs are not queried when no due branch requires a pull request") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("feature/x")
            every { harness.gitService.newOriginBranches(any(), any()) } returns listOf("feature/x")
            every { harness.gitService.originHeadCommit("feature/x", any()) } returns "commit-x"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("feature/x" to "commit-x")
            verify(exactly = 0) { harness.gitService.pullRequestHeads(any()) }
        }

        test("a disabled pull-request gate builds gated branches on plain-git origins without querying pull-request refs") {
            val harness =
                Harness(
                    WerkatorConfig(
                        watcher = WatcherConfig(pullRequestGate = false),
                        branches = mapOf("default" to BranchConfig(requirePullRequest = true)),
                    ),
                )
            every { harness.gitService.originBranches(any()) } returns listOf("feature/no-pr")
            every { harness.gitService.newOriginBranches(any(), any()) } returns listOf("feature/no-pr")
            every { harness.gitService.originHeadCommit("feature/no-pr", any()) } returns "commit-solo"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("feature/no-pr" to "commit-solo")
            verify(exactly = 0) { harness.gitService.pullRequestHeads(any()) }
        }

        test("a branch entry overrides requirePullRequest from the default entry") {
            val harness =
                Harness(
                    WerkatorConfig(
                        branches =
                            mapOf(
                                "default" to BranchConfig(requirePullRequest = true),
                                "main" to BranchConfig(requirePullRequest = false),
                            ),
                    ),
                )
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("main" to "commit-main")
        }

        test("an auto build requiring a pull request is skipped and its slot stays untriggered") {
            val harness =
                Harness(
                    WerkatorConfig(
                        branches =
                            mapOf(
                                "default" to BranchConfig(),
                                "main" to
                                    BranchConfig(
                                        requirePullRequest = true,
                                        autoBuild = AutoBuildConfig(enabled = true, times = listOf("11:00")),
                                    ),
                            ),
                    ),
                )
            harness.seed("main", BuildStatus.SUCCESS, commit = "commit-abc")
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-abc"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds.shouldBeEmpty()
            harness.autoBuildState().isTriggered("main", LocalDate.parse("2026-07-07"), "11:00").shouldBeFalse()
        }

        test("auto builds rebuild the already built commit once per day and slot") {
            val harness = Harness(autoBuildConfig("01:00", "11:00", "13:00"))
            harness.seed("main", BuildStatus.SUCCESS, commit = "commit-abc")
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-abc"

            harness.watcher.poll(harness.workingDir)
            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("main" to "commit-abc")
            // the deprecated branch schedule rebuilds the branch's own pool with the default build
            verify { harness.buildExecutor.startBuild("main", "commit-abc", any(), BuildDefinition.DEFAULT) }
            harness.autoBuildState().isTriggered("main", LocalDate.parse("2026-07-07"), "11:00").shouldBeTrue()
        }

        test("a scheduled build definition fires for its selected branches under its own pool") {
            val harness =
                Harness(
                    WerkatorConfig(
                        buildDefinitions =
                            mapOf(
                                "pitest" to
                                    BuildDefinition(
                                        trigger = TriggerConfig(atTimes = listOf("11:00"), branches = listOf("main", "release/*")),
                                    ),
                            ),
                    ),
                )
            // the branch's own pool is busy; the pitest pool is its own and not blocked by it
            harness.seed("main", BuildStatus.RUNNING, commit = "commit-abc")
            every { harness.gitService.originBranches(any()) } returns listOf("main", "release/1.x", "feature/x")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-abc"
            every { harness.gitService.originHeadCommit("release/1.x", any()) } returns "commit-rel"

            harness.watcher.poll(harness.workingDir)
            harness.watcher.poll(harness.workingDir)

            // glob selector: main and release/1.x fire once, feature/x is not selected
            harness.startedBuilds shouldContainExactlyInAnyOrder
                listOf("main" to "commit-abc", "release/1.x" to "commit-rel")
            verify { harness.buildExecutor.startBuild("main", "commit-abc", any(), "pitest") }
            harness.autoBuildState().isTriggered("main@pitest", LocalDate.parse("2026-07-07"), "11:00").shouldBeTrue()
        }

        test("a scheduled build definition with activeWithin skips branches without recent commits") {
            val harness =
                Harness(
                    WerkatorConfig(
                        buildDefinitions =
                            mapOf("pitest" to BuildDefinition(trigger = TriggerConfig(atTimes = listOf("11:00"), activeWithin = "24h"))),
                    ),
                )
            every { harness.gitService.originBranches(any()) } returns listOf("active", "dormant")
            every { harness.gitService.originHeadCommit("active", any()) } returns "commit-act"
            every { harness.gitService.originBranchCommitTimes(any()) } returns
                mapOf(
                    "active" to noon.minusSeconds(3600),
                    "dormant" to noon.minus(Duration.ofDays(10)),
                )

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("active" to "commit-act")
            verify { harness.buildExecutor.startBuild("active", "commit-act", any(), "pitest") }
        }

        test("an onPush build definition builds the changed branches it selects") {
            val harness =
                Harness(
                    WerkatorConfig(
                        buildDefinitions =
                            mapOf("lint" to BuildDefinition(trigger = TriggerConfig(onPush = true, branches = listOf("main")))),
                    ),
                )
            every { harness.gitService.originBranches(any()) } returns listOf("main", "feature/x")
            every { harness.gitService.localBranches(any()) } returns listOf("main", "feature/x")
            every { harness.gitService.hasNewCommits(any(), any()) } returns true
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"
            every { harness.gitService.originHeadCommit("feature/x", any()) } returns "commit-feat"

            harness.watcher.poll(harness.workingDir)

            // the implicit default build covers both branches; lint only selects main
            verify { harness.buildExecutor.startBuild("main", "commit-main", any(), BuildDefinition.DEFAULT) }
            verify { harness.buildExecutor.startBuild("feature/x", "commit-feat", any(), BuildDefinition.DEFAULT) }
            verify { harness.buildExecutor.startBuild("main", "commit-main", any(), "lint") }
            verify(exactly = 0) { harness.buildExecutor.startBuild("feature/x", "commit-feat", any(), "lint") }
        }

        test("builds.default with onPush false disables the implicit on-push build") {
            val harness =
                Harness(WerkatorConfig(buildDefinitions = mapOf("default" to BuildDefinition(trigger = TriggerConfig(onPush = false)))))
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds.shouldBeEmpty()
        }

        test("a commit-triggered build belongs to the default build definition") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"

            harness.watcher.poll(harness.workingDir)

            verify { harness.buildExecutor.startBuild("main", "commit-main", any(), BuildDefinition.DEFAULT) }
        }

        test("a build definition committed on a branch fires for that branch, without any entry in the primary config") {
            val harness = Harness()
            val branchLayer =
                WerkatorConfig(
                    buildDefinitions = mapOf("pitest" to BuildDefinition(trigger = TriggerConfig(atTimes = listOf("11:00")))),
                )
            every { harness.gitService.originBranches(any()) } returns listOf("main", "experiment")
            every { harness.gitService.originBranchHeads(any()) } returns
                mapOf("main" to "commit-main", "experiment" to "commit-exp")
            every { harness.gitService.showFileAtCommit("commit-exp", Watcher.CONFIG_FILE, any()) } returns "branch-yaml"
            every { harness.configLoader.loadWithBranchLayer(any(), "branch-yaml") } returns branchLayer
            every { harness.gitService.originHeadCommit("experiment", any()) } returns "commit-exp"
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("experiment" to "commit-exp")
            verify { harness.buildExecutor.startBuild("experiment", "commit-exp", any(), "pitest") }
            harness
                .autoBuildState()
                .isTriggered("experiment@pitest", LocalDate.parse("2026-07-07"), "11:00")
                .shouldBeTrue()
        }

        test("a branch config committed under the pre-rename name is still read") {
            val harness = Harness()
            val branchLayer =
                WerkatorConfig(
                    buildDefinitions = mapOf("pitest" to BuildDefinition(trigger = TriggerConfig(atTimes = listOf("11:00")))),
                )
            every { harness.gitService.originBranches(any()) } returns listOf("experiment")
            every { harness.gitService.originBranchHeads(any()) } returns mapOf("experiment" to "commit-exp")
            every { harness.gitService.showFileAtCommit("commit-exp", ".gittally.yml", any()) } returns "branch-yaml"
            every { harness.configLoader.loadWithBranchLayer(any(), "branch-yaml") } returns branchLayer
            every { harness.gitService.originHeadCommit("experiment", any()) } returns "commit-exp"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("experiment" to "commit-exp")
        }

        test("a build definition committed on a branch never schedules another branch") {
            val harness = Harness()
            val branchLayer =
                WerkatorConfig(
                    buildDefinitions =
                        mapOf("pitest" to BuildDefinition(trigger = TriggerConfig(atTimes = listOf("11:00"), branches = listOf("main")))),
                )
            every { harness.gitService.originBranches(any()) } returns listOf("main", "experiment")
            every { harness.gitService.originBranchHeads(any()) } returns
                mapOf("main" to "commit-main", "experiment" to "commit-exp")
            every { harness.gitService.showFileAtCommit("commit-exp", Watcher.CONFIG_FILE, any()) } returns "branch-yaml"
            every { harness.configLoader.loadWithBranchLayer(any(), "branch-yaml") } returns branchLayer
            every { harness.gitService.originHeadCommit(any(), any()) } returns "commit-any"

            harness.watcher.poll(harness.workingDir)

            // the definition selects main, but it is only known on experiment — so nothing is built
            harness.startedBuilds.shouldBeEmpty()
        }

        test("a branch's committed config is read from git again only after the branch moved") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.originBranchHeads(any()) } returns mapOf("main" to "commit-1")

            harness.watcher.poll(harness.workingDir)
            harness.watcher.poll(harness.workingDir)
            verify(exactly = 1) { harness.gitService.showFileAtCommit("commit-1", Watcher.CONFIG_FILE, any()) }

            every { harness.gitService.originBranchHeads(any()) } returns mapOf("main" to "commit-2")
            harness.watcher.poll(harness.workingDir)

            verify(exactly = 1) { harness.gitService.showFileAtCommit("commit-2", Watcher.CONFIG_FILE, any()) }
        }

        test("an edited primary config takes effect without the branch moving") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.originBranchHeads(any()) } returns mapOf("main" to "commit-1")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-1"

            harness.watcher.poll(harness.workingDir)
            harness.startedBuilds.shouldBeEmpty()

            // the machine config gains a scheduled build while the branch stays where it is:
            // caching the definitions by head commit alone would never notice
            val edited =
                WerkatorConfig(
                    buildDefinitions = mapOf("nightly" to BuildDefinition(trigger = TriggerConfig(atTimes = listOf("11:00")))),
                )
            every { harness.configLoader.load(any()) } returns edited
            every { harness.configLoader.loadWithBranchLayer(any(), anyNullable()) } returns edited

            harness.watcher.poll(harness.workingDir)

            verify { harness.buildExecutor.startBuild("main", "commit-1", any(), "nightly") }
        }

        test("an unreadable branch config falls back to the primary definitions instead of failing the poll") {
            val harness = Harness()
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.localBranches(any()) } returns listOf("main")
            every { harness.gitService.hasNewCommits("main", any()) } returns true
            every { harness.gitService.originBranchHeads(any()) } returns mapOf("main" to "commit-main")
            every { harness.gitService.showFileAtCommit("commit-main", Watcher.CONFIG_FILE, any()) } returns "broken"
            every { harness.configLoader.loadWithBranchLayer(any(), "broken") } throws
                RuntimeException("mapping problem")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-main"

            harness.watcher.poll(harness.workingDir)

            harness.watcher
                .state()
                .lastPollError
                .shouldBeNull()
            verify { harness.buildExecutor.startBuild("main", "commit-main", any(), BuildDefinition.DEFAULT) }
        }

        test("an auto-build slot stays untriggered while the branch is still building") {
            val harness = Harness(autoBuildConfig("11:00"))
            harness.seed("main", BuildStatus.RUNNING, commit = "commit-abc")
            every { harness.gitService.originBranches(any()) } returns listOf("main")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-abc"

            harness.watcher.poll(harness.workingDir)

            harness.startedBuilds.shouldBeEmpty()
            harness.autoBuildState().isTriggered("main", LocalDate.parse("2026-07-07"), "11:00").shouldBeFalse()
        }

        test("startup recovery marks stale builds interrupted and re-enqueues them") {
            val harness = Harness()
            harness.seed("main", BuildStatus.RUNNING, commit = "commit-1")
            harness.seed("feature/a", BuildStatus.INTERRUPTED, commit = "commit-2")
            harness.seed("queued", BuildStatus.PENDING, commit = "commit-3")
            harness.seed("done", BuildStatus.SUCCESS, commit = "commit-4")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-1"
            every { harness.gitService.originHeadCommit("feature/a", any()) } returns "commit-2"
            every { harness.gitService.originHeadCommit("queued", any()) } returns "commit-3"

            harness.watcher.recoverOnStartup(harness.workingDir)

            harness.startedBuilds shouldContainExactlyInAnyOrder
                listOf("main" to "commit-1", "feature/a" to "commit-2", "queued" to "commit-3")
            harness.repository.latestFor("main")!!.status shouldBe BuildStatus.INTERRUPTED
            harness.repository.latestFor("queued")!!.status shouldBe BuildStatus.INTERRUPTED
            harness.repository.latestFor("done")!!.status shouldBe BuildStatus.SUCCESS
        }

        test("startup recovery skips branches gone from origin and survives a failing fetch") {
            val harness = Harness()
            every { harness.gitService.fetchOrigin(any()) } throws RuntimeException("origin unreachable")
            harness.seed("gone", BuildStatus.INTERRUPTED, commit = "commit-1")
            harness.seed("main", BuildStatus.INTERRUPTED, commit = "commit-2")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-2"

            harness.watcher.recoverOnStartup(harness.workingDir)

            harness.startedBuilds shouldContainExactly listOf("main" to "commit-2")
        }

        test("startup recovery re-enqueues an interrupted build under its recorded build definition") {
            val harness = Harness()
            harness.seed("main", BuildStatus.INTERRUPTED, commit = "commit-1", build = "pitest")
            every { harness.gitService.originHeadCommit("main", any()) } returns "commit-1"

            harness.watcher.recoverOnStartup(harness.workingDir)

            // otherwise a restart mid-nightly-build would repeat it as a regular build in the wrong pool
            verify { harness.buildExecutor.startBuild("main", "commit-1", any(), "pitest") }
        }

        test("startup recovery closes out an orphaned PENDING build of a branch gone from origin") {
            val harness = Harness()
            val orphan = harness.seed("gone", BuildStatus.PENDING, commit = "commit-1")

            harness.watcher.recoverOnStartup(harness.workingDir)

            // PENDING is prune-immune; left as-is, the gone branch could never be pruned
            harness.startedBuilds.shouldBeEmpty()
            harness.repository
                .history()
                .first { it.artifactKey == orphan.artifactKey }
                .status shouldBe BuildStatus.INTERRUPTED
        }

        test("poll prunes results, artifacts, and worktrees of branches gone from origin") {
            val harness = Harness()
            harness.seed("main", BuildStatus.SUCCESS, commit = "commit-1")
            harness.seed("gone", BuildStatus.SUCCESS, commit = "commit-2")
            val keptWorktree = harness.worktreeDir("main")
            val removedWorktree = harness.worktreeDir("gone")
            every { harness.gitService.originBranches(any()) } returns listOf("main")

            harness.watcher.poll(harness.workingDir)

            harness.repository.history().map { it.branch } shouldContainExactly listOf("main")
            verify {
                harness.artifactStore.prune(
                    match { results -> results.map { it.branch } == listOf("main") },
                )
            }
            Files.exists(keptWorktree).shouldBeTrue()
            Files.exists(removedWorktree).shouldBeFalse()
            verify { harness.gitService.worktreePrune(any()) }
        }

        test("poll keeps the latest green build beyond retention unless keepLatestGreen is disabled") {
            val keeping = Harness(WerkatorConfig(artifacts = ArtifactsConfig(retentionPerBranch = 1)))
            keeping.seed("main", BuildStatus.SUCCESS, commit = "commit-1")
            keeping.seed("main", BuildStatus.FAILED, commit = "commit-2")
            every { keeping.gitService.originBranches(any()) } returns listOf("main")

            keeping.watcher.poll(keeping.workingDir)

            keeping.repository.history().map { it.status } shouldContainExactly
                listOf(BuildStatus.FAILED, BuildStatus.SUCCESS)

            val dropping =
                Harness(WerkatorConfig(artifacts = ArtifactsConfig(retentionPerBranch = 1, keepLatestGreen = false)))
            dropping.seed("main", BuildStatus.SUCCESS, commit = "commit-1")
            dropping.seed("main", BuildStatus.FAILED, commit = "commit-2")
            every { dropping.gitService.originBranches(any()) } returns listOf("main")

            dropping.watcher.poll(dropping.workingDir)

            dropping.repository.history().map { it.status } shouldContainExactly listOf(BuildStatus.FAILED)
        }

        test("poll drops builds older than retentionMaxAge but keeps the branch's newest build") {
            // seeds start one hour before the fixed clock, so a 30m age limit cuts them off
            val harness = Harness(WerkatorConfig(artifacts = ArtifactsConfig(retentionMaxAge = "30m")))
            harness.seed("main", BuildStatus.FAILED, commit = "commit-1")
            harness.seed("main", BuildStatus.FAILED, commit = "commit-2")
            every { harness.gitService.originBranches(any()) } returns listOf("main")

            harness.watcher.poll(harness.workingDir)

            harness.repository.history().map { it.commit } shouldContainExactly listOf("commit-2")
        }

        test("worktrees of queued or running builds are never pruned") {
            val harness = Harness()
            harness.seed("busy", BuildStatus.RUNNING, commit = "commit-1")
            val busyWorktree = harness.worktreeDir("busy")
            every { harness.gitService.originBranches(any()) } returns listOf("busy")

            harness.watcher.poll(harness.workingDir)

            Files.exists(busyWorktree).shouldBeTrue()
        }

        test("start runs recovery plus an immediate first poll; stop halts the loop") {
            val harness = Harness()
            val fetches = CountDownLatch(2)
            every { harness.gitService.fetchOrigin(any()) } answers { fetches.countDown() }

            harness.watcher.start(harness.workingDir)

            fetches.await(5, TimeUnit.SECONDS).shouldBeTrue()
            harness.watcher
                .state()
                .running
                .shouldBeTrue()
            shouldThrow<IllegalStateException> { harness.watcher.start(harness.workingDir) }

            harness.watcher.stop()

            harness.watcher
                .state()
                .running
                .shouldBeFalse()
        }
    }
}

/**
 * Collects this spec's [Watcher] log messages; the returned lambda reads them at any point.
 * The level is lowered explicitly — the test JVM logs at WARN, which would drop the very
 * INFO line that says the fetch recovered.
 */
private fun captureWatcherLog(): () -> List<String> {
    val logger = LoggerFactory.getLogger(Watcher::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    logger.level = Level.INFO
    logger.addAppender(appender)
    return { appender.list.map { it.formattedMessage } }
}
