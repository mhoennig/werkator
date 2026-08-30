package de.hoennig.werkator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class StateDirMigrationTest : FunSpec() {
    private fun repoWithLegacyState(): Path {
        val dir = Files.createTempDirectory("werkator-state")
        Files.createDirectories(dir.resolve(".git/gittally"))
        dir.resolve(".git/gittally/build-results.json").toFile().writeText("[]")
        return dir
    }

    init {
        test("moves the pre-rename state directory to its current path") {
            val dir = repoWithLegacyState()

            StateDirMigration.migrateIfNeeded(dir)

            Files.exists(dir.resolve(".git/gittally")).shouldBeFalse()
            dir.resolve(".git/werkator/build-results.json").toFile().readText() shouldBe "[]"
        }

        test("drops the moved worktrees, because they point at their old path") {
            val dir = repoWithLegacyState()
            Files.createDirectories(dir.resolve(".git/gittally/worktrees/main"))

            StateDirMigration.migrateIfNeeded(dir)

            Files.exists(dir.resolve(".git/werkator/worktrees")).shouldBeFalse()
            // the rest of the state survives the drop
            Files.exists(dir.resolve(".git/werkator/build-results.json")).shouldBeTrue()
        }

        test("leaves both alone when the current directory already exists") {
            val dir = repoWithLegacyState()
            Files.createDirectories(dir.resolve(".git/werkator"))
            dir.resolve(".git/werkator/build-results.json").toFile().writeText("[\"live\"]")

            StateDirMigration.migrateIfNeeded(dir)

            // which of the two is the live state is not guessed
            dir.resolve(".git/werkator/build-results.json").toFile().readText() shouldBe "[\"live\"]"
            Files.exists(dir.resolve(".git/gittally")).shouldBeTrue()
        }

        test("does nothing where there is no pre-rename directory") {
            val dir = Files.createTempDirectory("werkator-state")
            Files.createDirectories(dir.resolve(".git"))

            StateDirMigration.migrateIfNeeded(dir)

            Files.exists(dir.resolve(".git/werkator")).shouldBeFalse()
        }
    }
}
