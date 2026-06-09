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
            config.branches["default"]!!.buildCommand shouldBe BranchConfig().buildCommand
        }

        test("repo install config overrides project config for same keys") {
            val dir = Files.createTempDirectory("gittally-test")
            dir.resolve(".gittally.yml").toFile().writeText(
                """
                gitea:
                  owner: my-org
                  token: from-project
                """.trimIndent(),
            )
            dir.resolve(".git/gittally").toFile().mkdirs()
            dir.resolve(".git/gittally/config.yml").toFile().writeText(
                """
                gitea:
                  token: from-repo-install
                """.trimIndent(),
            )
            val config = loader.load(dir)
            config.gitea.owner shouldBe "my-org"
            config.gitea.token shouldBe "from-repo-install"
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

        test("toYaml serializes GitTallyConfig with all sections") {
            val yaml = loader.toYaml(GitTallyConfig())
            yaml shouldContain "server:"
            yaml shouldContain "gitea:"
            yaml shouldContain "artifacts:"
            yaml shouldContain "watcher:"
            yaml shouldContain "branches:"
        }
    }
}
