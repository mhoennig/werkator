package de.hoennig.werkator.repo

import de.hoennig.werkator.config.ConfigLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Paths

class RepoContextsTest : FunSpec() {
    private val contexts = RepoContexts(ConfigLoader())

    init {
        test("a context is named after its directory and keeps its state inside the repository") {
            val dir = Files.createTempDirectory("werkator-repo-context-test").resolve("werkbaum")
            Files.createDirectories(dir)

            val repo = contexts.open(dir)

            repo.name shouldBe "werkbaum"
            repo.workingDir shouldBe dir
            repo.artifactStore
                .rootDir()
                .fileName
                .toString() shouldBe
                de.hoennig.werkator.build.ArtifactKeys
                    .repoKey(dir)
        }

        test("the current directory resolves to the same name as its absolute path") {
            contexts.open(Paths.get(".")).name shouldBe RepoContexts.defaultName(Paths.get(".").toAbsolutePath())
        }

        test("a filesystem root has no basename and gets the fallback name") {
            RepoContexts.defaultName(Paths.get("/")) shouldBe "repository"
        }

        test("the name can be overridden per entry, as the registry will do") {
            contexts.open(Paths.get("."), name = "custom").name shouldBe "custom"
        }
    }
}
