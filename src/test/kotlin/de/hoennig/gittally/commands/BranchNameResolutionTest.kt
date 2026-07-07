package de.hoennig.gittally.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BranchNameResolutionTest : FunSpec() {
    private val candidates = listOf("main", "main-backup", "feature/login", "feature/logout", "hotfix/1.2.3")

    init {
        test("a unique fragment resolves to the full branch name") {
            BranchNameResolution.resolve("login", candidates) shouldBe
                BranchNameResolution.Resolved("feature/login")
        }

        test("an exact branch name wins even when other branches contain it") {
            BranchNameResolution.resolve("main", candidates) shouldBe
                BranchNameResolution.Resolved("main")
        }

        test("an ambiguous fragment reports all matching candidates") {
            BranchNameResolution.resolve("feature/log", candidates) shouldBe
                BranchNameResolution.Ambiguous(listOf("feature/login", "feature/logout"))
        }

        test("a fragment without any match reports no match") {
            BranchNameResolution.resolve("release", candidates) shouldBe
                BranchNameResolution.NoMatch
        }
    }
}
