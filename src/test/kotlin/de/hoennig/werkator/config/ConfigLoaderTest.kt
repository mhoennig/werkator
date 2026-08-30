package de.hoennig.werkator.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import java.nio.file.Files
import java.util.Properties

class ConfigLoaderTest : FunSpec() {
    private val loader = ConfigLoader()

    /** A loader that knows which Werkator it is, for the `werkator.version` checks. */
    private fun loaderRunning(version: String): ConfigLoader {
        val provider = mockk<ObjectProvider<BuildProperties>>()
        every { provider.getIfAvailable() } returns BuildProperties(Properties().apply { setProperty("version", version) })
        return ConfigLoader(provider)
    }

    init {
        test("returns defaults when no config files exist") {
            val dir = Files.createTempDirectory("werkator-test")
            loader.load(dir) shouldBe WerkatorConfig()
        }

        test("loadRaw returns empty map when no config files exist") {
            val dir = Files.createTempDirectory("werkator-test")
            loader.loadRaw(dir).shouldBeEmpty()
        }

        test("reads gitea config from .werkator.yml") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                gitea:
                  owner: my-org
                  repo: my-repo
                """.trimIndent(),
            )
            val config = loader.load(dir)
            config.gitea.owner shouldBe "my-org"
            config.gitea.repo shouldBe "my-repo"
        }

        test("falls back to the pre-rename .gittally.yml at the repository root") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".gittally.yml").toFile().writeText(
                """
                gitea:
                  owner: my-org
                  repo: my-repo
                """.trimIndent(),
            )
            loader.load(dir).gitea.owner shouldBe "my-org"
        }

        test("falls back to the pre-rename machine config under .git/gittally") {
            val dir = Files.createTempDirectory("werkator-test")
            Files.createDirectories(dir.resolve(".git/gittally"))
            dir.resolve(".git/gittally/.gittally.yml").toFile().writeText(
                """
                git:
                  account: ci-user
                """.trimIndent(),
            )
            loader.load(dir).git.account shouldBe "ci-user"
        }

        test("the current name wins where both exist, so a half-done rename is not merged") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText("gitea:\n  owner: current\n")
            dir.resolve(".gittally.yml").toFile().writeText("gitea:\n  owner: legacy\n  repo: legacy-repo\n")
            val config = loader.load(dir)
            config.gitea.owner shouldBe "current"
            // not merged: the old file is a leftover, not a layer
            config.gitea.repo shouldBe ""
        }

        test("a branch whose config is committed under the pre-rename name is still read as the branch layer") {
            val dir = Files.createTempDirectory("werkator-test")
            val worktree = Files.createTempDirectory("werkator-worktree")
            worktree.resolve(".gittally.yml").toFile().writeText(
                """
                builds:
                  default:
                    buildCommand: ./gradlew fromBranch
                """.trimIndent(),
            )
            loader.loadForWorktree(dir, worktree).buildSettings("any-branch", "default").buildCommand shouldBe
                "./gradlew fromBranch"
        }

        test("reads executor.maxConcurrent and defaults it to 1") {
            val dir = Files.createTempDirectory("werkator-test")
            loader.load(dir).executor.maxConcurrent shouldBe 1

            dir.resolve(".werkator.yml").toFile().writeText(
                """
                executor:
                  maxConcurrent: 3
                """.trimIndent(),
            )
            loader.load(dir).executor.maxConcurrent shouldBe 3
        }

        test("the builds section holds named build definitions") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                executor:
                  maxConcurrent: 2
                builds:
                  pitest:
                    trigger:
                      atTimes: ["01:00"]
                      branches: ["master", "release/*"]
                      activeWithin: 24h
                    buildCommand: ./gradlew piTestFull
                """.trimIndent(),
            )

            val config = loader.load(dir)

            config.executor.maxConcurrent shouldBe 2
            config.buildDefinitions shouldBe
                mapOf(
                    "pitest" to
                        BuildDefinition(
                            trigger =
                                TriggerConfig(
                                    atTimes = listOf("01:00"),
                                    branches = listOf("master", "release/*"),
                                    activeWithin = "24h",
                                ),
                            buildCommand = "./gradlew piTestFull",
                        ),
                )
            // the implicit default build (onPush over all branches) stays in place
            config.effectiveBuildDefinitions()["default"] shouldBe BuildDefinition(trigger = TriggerConfig(onPush = true))
        }

        test("an explicit builds.default entry overrides the implicit default build") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  default:
                    trigger:
                      onPush: false
                """.trimIndent(),
            )

            loader.load(dir).effectiveBuildDefinitions()["default"] shouldBe BuildDefinition(trigger = TriggerConfig(onPush = false))
        }

        test("a config that needs a newer Werkator is refused, naming the file and both versions") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                werkator:
                  version:
                    since: "0.9.16"
                """.trimIndent(),
            )

            val error = shouldThrow<ConfigVersionException> { loaderRunning("0.9.15").load(dir) }

            error.message.shouldNotBeNull().let {
                it shouldContain ".werkator.yml"
                it shouldContain "0.9.16"
                it shouldContain "0.9.15"
                it shouldContain "roll back"
            }
        }

        test("a config within its declared range loads, and exceeding only the ceiling still loads") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                werkator:
                  version:
                    since: "0.9.16"
                    below: "1.0"
                gitea:
                  owner: my-org
                """.trimIndent(),
            )

            loaderRunning("0.9.16").load(dir).gitea.owner shouldBe "my-org"
            // beyond `below`: a warning, never a refusal — an unmaintained marker must not stop a CI
            loaderRunning("1.4.0").load(dir).gitea.owner shouldBe "my-org"
            loaderRunning("0.9.16").load(dir).werkator.version shouldBe
                VersionRequirement(since = "0.9.16", below = "1.0")
        }

        test("an incompatible branch config is refused as the branch's problem, not the server's") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText("gitea:\n  owner: my-org")

            val error =
                shouldThrow<ConfigVersionException> {
                    loaderRunning("0.9.15").loadWithBranchLayer(
                        dir,
                        """
                        werkator:
                          version:
                            since: "2.0.0"
                        """.trimIndent(),
                    )
                }

            error.message.shouldNotBeNull().let {
                it shouldContain "branch"
                it shouldContain "the other branches keep building"
            }
            // the primary config alone is untouched by the branch's declaration
            loaderRunning("0.9.15").load(dir).gitea.owner shouldBe "my-org"
        }

        test("the machine config is checked as its own file") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".git/werkator").toFile().mkdirs()
            dir.resolve(".git/werkator/.werkator.yml").toFile().writeText(
                """
                werkator:
                  version:
                    since: "1.0.0"
                """.trimIndent(),
            )

            shouldThrow<ConfigVersionException> {
                loaderRunning("0.9.16").load(dir)
            }.message.shouldNotBeNull() shouldContain ".git/werkator/.werkator.yml"
        }

        test("a leftover builds.maxConcurrent is ignored instead of failing the config") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  maxConcurrent: 1
                  pitest:
                    buildCommand: ./gradlew piTestFull
                """.trimIndent(),
            )

            val config = loader.load(dir)

            config.executor.maxConcurrent shouldBe 1
            config.buildDefinitions.keys shouldBe setOf("pitest")
        }

        test("a branch may redefine the builds section for its own builds") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  pitest:
                    trigger:
                      atTimes: ["01:00"]
                    buildCommand: ./gradlew piTestPartial
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("werkator-test-worktree")
            worktree.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  pitest:
                    buildCommand: ./gradlew piTestFull
                  experiment:
                    trigger:
                      onPush: true
                """.trimIndent(),
            )

            val config = loader.loadForWorktree(dir, worktree)

            // the branch layer merges into the definition instead of replacing it
            config.buildDefinitions.getValue("pitest").buildCommand shouldBe "./gradlew piTestFull"
            config.buildDefinitions
                .getValue("pitest")
                .trigger.atTimes shouldBe listOf("01:00")
            config.buildDefinitions
                .getValue("experiment")
                .trigger.onPush shouldBe true
        }

        test("a branch cannot raise the concurrency limit or reach the sandbox policy through a build definition") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  default:
                    requirePullRequest: true
                    statusContext: werkator
                    docker:
                      enabled: true
                      network: none
                      image: host-image
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("werkator-test-worktree")
            worktree.resolve(".werkator.yml").toFile().writeText(
                """
                executor:
                  maxConcurrent: 99
                watcher:
                  pullRequestGate: false
                builds:
                  default:
                    requirePullRequest: false
                    statusContext: werkator/impersonated
                    docker:
                      enabled: false
                      network: host
                      image: attacker-image
                """.trimIndent(),
            )

            val config = loader.loadForWorktree(dir, worktree)
            val settings = config.buildSettings("any-branch", "default")

            config.executor.maxConcurrent shouldBe 1
            config.watcher.pullRequestGate shouldBe true
            settings.requirePullRequest shouldBe true
            settings.docker.enabled shouldBe true
            settings.docker.network shouldBe "none"
            settings.statusContext shouldBe "werkator"
            // everything that describes the build itself stays the branch's own business
            settings.docker.image shouldBe "attacker-image"
        }

        test("a build the branch invents inherits the host's sandbox policy") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  default:
                    requirePullRequest: true
                    docker:
                      enabled: true
                      network: none
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("werkator-test-worktree")
            worktree.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  invented:
                    trigger:
                      atTimes: ["03:00"]
                    buildCommand: ./gradlew whatever
                    docker:
                      enabled: false
                      network: host
                """.trimIndent(),
            )

            // the host has never heard of this build, so there is no lower layer to fall
            // back to — it must inherit the policy from builds.default, not the data class
            val settings = loader.loadForWorktree(dir, worktree).buildSettings("any-branch", "invented")

            settings.buildCommand shouldBe "./gradlew whatever"
            settings.docker.enabled shouldBe true
            settings.docker.network shouldBe "none"
            settings.requirePullRequest shouldBe true
        }

        test("an exclusion pattern takes a branch out of a build that would otherwise select it") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  default:
                    trigger:
                      onPush: true
                      branches: ["*", "!master"]
                  release:
                    trigger:
                      onPush: true
                      branches: ["master"]
                """.trimIndent(),
            )

            val definitions = loader.load(dir).buildDefinitions

            definitions
                .getValue("default")
                .trigger
                .selectsByName("mihoe/feature")
                .shouldBeTrue()
            definitions
                .getValue("default")
                .trigger
                .selectsByName("master")
                .shouldBeFalse()
            definitions
                .getValue("release")
                .trigger
                .selectsByName("master")
                .shouldBeTrue()
            definitions
                .getValue("release")
                .trigger
                .selectsByName("mihoe/feature")
                .shouldBeFalse()
        }

        test("an exclusion wins over a matching pattern, whatever their order") {
            val trigger = TriggerConfig(branches = listOf("!release/hotfix", "release/*"))

            trigger.selectsByName("release/1.0").shouldBeTrue()
            trigger.selectsByName("release/hotfix").shouldBeFalse()
        }

        test("a trigger key written outside the trigger block is refused, naming the definition") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  nightly:
                    atTimes: ["01:00"]
                    buildCommand: ./gradlew check
                """.trimIndent(),
            )

            // ignoring it would leave the build without a trigger — a job that silently
            // stops running is worse than a configuration that refuses to load
            val thrown = shouldThrow<ConfigFormatException> { loader.load(dir) }

            thrown.message.shouldContain(".werkator.yml")
            thrown.message.shouldContain("builds.nightly: atTimes")
            thrown.message.shouldContain("trigger:")
        }

        test("a branch writing its trigger flat fails only its own builds") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  default:
                    trigger:
                      onPush: true
                """.trimIndent(),
            )

            shouldThrow<ConfigFormatException> {
                loader.loadWithBranchLayer(
                    dir,
                    """
                    builds:
                      experiment:
                        onPush: true
                    """.trimIndent(),
                )
            }.message.shouldContain("this branch")
            // the primary config is untouched, so every other branch keeps building
            loader
                .load(dir)
                .buildDefinitions
                .getValue("default")
                .trigger.onPush
                .shouldBeTrue()
        }

        test("builds.default is the base of every other build, but never its trigger") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                builds:
                  default:
                    trigger:
                      onPush: true
                      branches: ["master"]
                    buildCommand: ./gradlew check
                    artifactDirs: [build/reports]
                    docker:
                      image: shared-image
                  nightly:
                    trigger:
                      atTimes: ["01:00"]
                    artifactDirs: [build/reports, build/libs]
                """.trimIndent(),
            )

            val nightly = loader.load(dir).buildDefinitions.getValue("nightly")

            nightly.buildCommand shouldBe "./gradlew check"
            nightly.docker?.image shouldBe "shared-image"
            nightly.artifactDirs shouldBe listOf("build/reports", "build/libs")
            // a trigger says when *this* build runs; inheriting it would fire every job at once
            nightly.trigger.onPush shouldBe false
            nightly.trigger.branches shouldBe emptyList<String>()
            nightly.trigger.atTimes shouldBe listOf("01:00")
        }

        test("branches is honored while no build is defined and ignored as soon as one is") {
            val dir = Files.createTempDirectory("werkator-test")
            val legacy =
                """
                branches:
                  default:
                    buildCommand: from-branches
                    docker:
                      enabled: true
                """.trimIndent()
            dir.resolve(".werkator.yml").toFile().writeText(legacy)

            // the leftover execution key is not a definition, so the legacy section still wins
            loader.load(dir).buildSettings("main", "default").buildCommand shouldBe "from-branches"
            dir.resolve(".werkator.yml").toFile().writeText("builds:\n  maxConcurrent: 1\n" + legacy)
            loader.load(dir).buildSettings("main", "default").buildCommand shouldBe "from-branches"

            dir.resolve(".werkator.yml").toFile().writeText(
                legacy +
                    "\n" +
                    """
                    builds:
                      default:
                        buildCommand: from-builds
                    """.trimIndent(),
            )

            val settings = loader.load(dir).buildSettings("main", "default")
            settings.buildCommand shouldBe "from-builds"
            settings.docker.enabled shouldBe false
        }

        test("repo install config overrides project config for same keys") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                gitea:
                  owner: original-org
                git:
                  token: from-project
                """.trimIndent(),
            )
            dir.resolve(".git/werkator").toFile().mkdirs()
            dir.resolve(".git/werkator/.werkator.yml").toFile().writeText(
                """
                gitea:
                  owner: override-org
                git:
                  token: from-repo-install
                """.trimIndent(),
            )
            val config = loader.load(dir)
            config.gitea.owner shouldBe "override-org"
            config.git.token shouldBe "from-repo-install"
        }

        test("loadRaw only returns explicitly set values") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: ./mvnw test
                """.trimIndent(),
            )
            val raw = loader.loadRaw(dir)

            @Suppress("UNCHECKED_CAST")
            val default = (raw["branches"] as Map<String, Any?>)["default"] as Map<String, Any?>
            default["buildCommand"] shouldBe "./mvnw test"
            default.containsKey("cleanCommand") shouldBe false
        }

        test("branch-specific config inherits from branches.default") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: ./mvnw test
                  main:
                    autoBuild:
                      enabled: true
                """.trimIndent(),
            )
            val config = loader.load(dir)
            config.branches["main"]!!.buildCommand shouldBe "./mvnw test"
            config.branches["main"]!!.autoBuild.enabled shouldBe true
        }

        test("branch-specific buildCommand overrides branches.default") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: ./mvnw test
                  release:
                    buildCommand: ./mvnw -P release test
                """.trimIndent(),
            )
            val config = loader.load(dir)
            config.branches["release"]!!.buildCommand shouldBe "./mvnw -P release test"
        }

        test("empty publicBaseUrl defaults to https://<nginx.serverName>/ when set") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                server:
                  nginx:
                    serverName: ci.example.org
                """.trimIndent(),
            )
            loader.load(dir).server.publicBaseUrl shouldBe "https://ci.example.org/"
        }

        test("explicit publicBaseUrl wins over the nginx.serverName default") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                server:
                  publicBaseUrl: https://other.example.org/
                  nginx:
                    serverName: ci.example.org
                """.trimIndent(),
            )
            loader.load(dir).server.publicBaseUrl shouldBe "https://other.example.org/"
        }

        test("publicBaseUrl stays empty without an nginx.serverName") {
            val dir = Files.createTempDirectory("werkator-test")
            loader.load(dir).server.publicBaseUrl shouldBe ""
        }

        test("loadForWorktree lets the worktree override build config (worktree > .git > project)") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-project
                """.trimIndent(),
            )
            dir.resolve(".git/werkator").toFile().mkdirs()
            dir.resolve(".git/werkator/.werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-git
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("werkator-worktree")
            worktree.resolve(".werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-worktree
                """.trimIndent(),
            )
            loader.loadForWorktree(dir, worktree).branches["default"]!!.buildCommand shouldBe "from-worktree"
        }

        test("loadForWorktree falls back to .git over project when the worktree sets nothing") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-project
                """.trimIndent(),
            )
            dir.resolve(".git/werkator").toFile().mkdirs()
            dir.resolve(".git/werkator/.werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-git
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("werkator-worktree")
            loader.loadForWorktree(dir, worktree).branches["default"]!!.buildCommand shouldBe "from-git"
        }

        test("loadForWorktree pins secrets and the docker sandbox policy to .git, but allows docker.image") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".git/werkator").toFile().mkdirs()
            dir.resolve(".git/werkator/.werkator.yml").toFile().writeText(
                """
                git:
                  token: real-secret
                server:
                  port: 9000
                branches:
                  default:
                    docker:
                      enabled: true
                      network: host
                      image: trusted-image
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("werkator-worktree")
            worktree.resolve(".werkator.yml").toFile().writeText(
                """
                git:
                  token: stolen
                server:
                  port: 1234
                branches:
                  default:
                    docker:
                      enabled: false
                      network: none
                      image: attacker-image
                """.trimIndent(),
            )
            val config = loader.loadForWorktree(dir, worktree)
            // pinned: never taken from the worktree
            config.git.token shouldBe "real-secret"
            config.server.port shouldBe 9000
            config.branches["default"]!!.docker.enabled shouldBe true
            config.branches["default"]!!.docker.network shouldBe "host"
            // overridable: the worktree wins
            config.branches["default"]!!.docker.image shouldBe "attacker-image"
        }

        test("loadWithBranchLayer applies a branch config read from git, pinning the same keys") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".git/werkator").toFile().mkdirs()
            dir.resolve(".git/werkator/.werkator.yml").toFile().writeText(
                """
                git:
                  token: real-secret
                builds:
                  default:
                    buildCommand: from-git
                """.trimIndent(),
            )

            val config =
                loader.loadWithBranchLayer(
                    dir,
                    """
                    git:
                      token: stolen
                    builds:
                      default:
                        buildCommand: from-branch
                      pitest:
                        trigger:
                          atTimes: ["03:00"]
                        buildCommand: ./gradlew piTestFull
                    """.trimIndent(),
                )

            config.git.token shouldBe "real-secret"
            config.buildSettings("main", "default").buildCommand shouldBe "from-branch"
            config.buildDefinitions
                .getValue("pitest")
                .trigger.atTimes shouldBe listOf("03:00")
        }

        test("loadWithBranchLayer without a branch config equals load") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: ./mvnw test
                """.trimIndent(),
            )

            loader.loadWithBranchLayer(dir, null) shouldBe loader.load(dir)
            loader.loadWithBranchLayer(dir, "") shouldBe loader.load(dir)
        }

        test("loadForWorktree without a worktree config equals load") {
            val dir = Files.createTempDirectory("werkator-test")
            dir.resolve(".werkator.yml").toFile().writeText(
                """
                gitea:
                  owner: my-org
                branches:
                  default:
                    buildCommand: ./mvnw test
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("werkator-worktree")
            loader.loadForWorktree(dir, worktree) shouldBe loader.load(dir)
        }

        test("toYaml serializes WerkatorConfig with all sections") {
            val yaml = loader.toYaml(WerkatorConfig())
            yaml shouldContain "server:"
            yaml shouldContain "git:"
            yaml shouldContain "gitea:"
            yaml shouldContain "artifacts:"
            yaml shouldContain "watcher:"
            yaml shouldContain "branches:"
        }
    }
}
