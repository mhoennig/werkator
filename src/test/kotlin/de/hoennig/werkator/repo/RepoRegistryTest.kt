package de.hoennig.werkator.repo

import de.hoennig.werkator.config.ConfigLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

class RepoRegistryTest : FunSpec() {
    private fun loaderWithHome(
        home: Path,
        version: String = "1.0.0",
    ): ConfigLoader {
        val provider = mockk<ObjectProvider<BuildProperties>>()
        every { provider.getIfAvailable() } returns BuildProperties(Properties().apply { setProperty("version", version) })
        return ConfigLoader(provider).apply { homeDir = home }
    }

    private fun gitRepo(
        parent: Path,
        name: String,
    ): Path = Files.createDirectories(parent.resolve(name)).also { Files.createDirectories(it.resolve(".git")) }

    private fun registry(loader: ConfigLoader) = RepoRegistry(loader, RepoContexts(loader))

    init {
        test("without a home config the registry is the current directory alone") {
            val home = Files.createTempDirectory("werkator-home")
            val registry = registry(loaderWithHome(home))

            registry.all().map { it.workingDir } shouldBe listOf(Paths.get("."))
            registry.current().workingDir shouldBe Paths.get(".")
        }

        test("every registry entry becomes a context, named after its directory unless the entry says otherwise") {
            val home = Files.createTempDirectory("werkator-home")
            val one = gitRepo(home, "repos/werkator")
            val two = gitRepo(home, "repos/werkbaum")
            home.resolve(".werkator.yml").toFile().writeText(
                """
                repositories:
                  - path: ~/repos/werkator
                  - path: $two
                    name: baum
                """.trimIndent(),
            )

            val registry = registry(loaderWithHome(home))

            registry.all().map { it.name to it.workingDir } shouldBe listOf("werkator" to one, "baum" to two)
            registry.byName("baum")?.workingDir shouldBe two
            registry.byName("nope") shouldBe null
            // the cwd is not registered, so the first entry is the default
            registry.current().name shouldBe "werkator"
        }

        test("an entry that is not a git repository aborts the start, naming the home file") {
            val home = Files.createTempDirectory("werkator-home")
            Files.createDirectories(home.resolve("not-a-repo"))
            home.resolve(".werkator.yml").toFile().writeText("repositories:\n  - path: ~/not-a-repo\n")

            val error = shouldThrow<IllegalStateException> { registry(loaderWithHome(home)).all() }

            error.message shouldContain home.resolve(".werkator.yml").toString()
            error.message shouldContain "not a git repository"
        }

        test("two entries resolving to the same name abort the start") {
            val home = Files.createTempDirectory("werkator-home")
            gitRepo(home, "a/werkator")
            gitRepo(home, "b/werkator")
            home.resolve(".werkator.yml").toFile().writeText("repositories:\n  - path: ~/a/werkator\n  - path: ~/b/werkator\n")

            val error = shouldThrow<IllegalStateException> { registry(loaderWithHome(home)).all() }

            error.message shouldContain "werkator"
            error.message shouldContain "distinct name"
        }

        test("a repository whose configuration must not be read is skipped, the others are served") {
            val home = Files.createTempDirectory("werkator-home")
            val fine = gitRepo(home, "fine")
            val broken = gitRepo(home, "broken")
            broken.resolve(".werkator.yml").toFile().writeText("werkator:\n  version:\n    since: \"9.9\"\n")
            home.resolve(".werkator.yml").toFile().writeText("repositories:\n  - path: ~/broken\n  - path: ~/fine\n")

            val registry = registry(loaderWithHome(home, version = "1.0.0"))

            registry.all().map { it.workingDir } shouldBe listOf(fine)
        }
    }
}
