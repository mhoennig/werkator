package de.hoennig.gittally.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class ConfigLoaderTest : FunSpec() {
    private val loader = ConfigLoader()

    init {
        test("returns defaults when no config files exist") {
            val dir = Files.createTempDirectory("gittally-test")
            loader.load(dir) shouldBe GitTallyConfig()
        }

        test("loadRaw returns empty map when no config files exist") {
            val dir = Files.createTempDirectory("gittally-test")
            loader.loadRaw(dir).shouldBeEmpty()
        }

        test("reads gitea config from .gittally.yml") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
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

        test("reads builds.maxConcurrent and defaults it to 1") {
            val dir = Files.createTempDirectory("gittally-test")
            loader.load(dir).builds.maxConcurrent shouldBe 1

            dir.resolve(".gittally.yml").toFile().writeText(
                """
                builds:
                  maxConcurrent: 3
                """.trimIndent(),
            )
            loader.load(dir).builds.maxConcurrent shouldBe 3
        }

        test("repo install config overrides project config for same keys") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
                """
                gitea:
                  owner: original-org
                git:
                  token: from-project
                """.trimIndent(),
            )
            dir.resolve(".git/gittally").toFile().mkdirs()
            dir.resolve(".git/gittally/.gittally.yml").toFile().writeText(
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
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
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
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
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
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
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
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
                """
                server:
                  nginx:
                    serverName: ci.example.org
                """.trimIndent(),
            )
            loader.load(dir).server.publicBaseUrl shouldBe "https://ci.example.org/"
        }

        test("explicit publicBaseUrl wins over the nginx.serverName default") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
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
            val dir = Files.createTempDirectory("gittally-test")
            loader.load(dir).server.publicBaseUrl shouldBe ""
        }

        test("loadForWorktree lets the worktree override build config (worktree > .git > project)") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-project
                """.trimIndent(),
            )
            dir.resolve(".git/gittally").toFile().mkdirs()
            dir.resolve(".git/gittally/.gittally.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-git
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("gittally-worktree")
            worktree.resolve(".gittally.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-worktree
                """.trimIndent(),
            )
            loader.loadForWorktree(dir, worktree).branches["default"]!!.buildCommand shouldBe "from-worktree"
        }

        test("loadForWorktree falls back to .git over project when the worktree sets nothing") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-project
                """.trimIndent(),
            )
            dir.resolve(".git/gittally").toFile().mkdirs()
            dir.resolve(".git/gittally/.gittally.yml").toFile().writeText(
                """
                branches:
                  default:
                    buildCommand: from-git
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("gittally-worktree")
            loader.loadForWorktree(dir, worktree).branches["default"]!!.buildCommand shouldBe "from-git"
        }

        test("loadForWorktree pins secrets and the docker sandbox policy to .git, but allows docker.image") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".git/gittally").toFile().mkdirs()
            dir.resolve(".git/gittally/.gittally.yml").toFile().writeText(
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
            val worktree = Files.createTempDirectory("gittally-worktree")
            worktree.resolve(".gittally.yml").toFile().writeText(
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

        test("loadForWorktree without a worktree config equals load") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
                """
                gitea:
                  owner: my-org
                branches:
                  default:
                    buildCommand: ./mvnw test
                """.trimIndent(),
            )
            val worktree = Files.createTempDirectory("gittally-worktree")
            loader.loadForWorktree(dir, worktree) shouldBe loader.load(dir)
        }

        test("toYaml serializes GitTallyConfig with all sections") {
            val yaml = loader.toYaml(GitTallyConfig())
            yaml shouldContain "server:"
            yaml shouldContain "git:"
            yaml shouldContain "gitea:"
            yaml shouldContain "artifacts:"
            yaml shouldContain "watcher:"
            yaml shouldContain "branches:"
        }
    }
}
