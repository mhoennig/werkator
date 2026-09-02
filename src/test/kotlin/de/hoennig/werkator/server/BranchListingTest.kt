package de.hoennig.werkator.server

import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant

class BranchListingTest : FunSpec() {
    private val gitService = mockk<GitService>()
    private val repository = mockk<BuildResultRepository>()
    private val repo =
        RepoContext(
            "test",
            java.nio.file.Paths
                .get("."),
            repository,
            mockk(),
        )
    private val listing = BranchListing(gitService)

    private val mainResult =
        BuildResult(
            branch = "main",
            commit = "0123456789abcdef0123456789abcdef01234567",
            status = BuildStatus.SUCCESS,
            startedAt = Instant.parse("2026-07-07T10:00:00Z"),
            duration = Duration.ofSeconds(83),
            artifactKey = "main-abc123-key",
        )

    init {
        beforeEach {
            every { repository.latestPerName() } returns emptyList()
        }

        test("a branch built only by named definitions loses its empty default row") {
            val releaseResult = mainResult.copy(build = "release", name = "master@release")
            every { gitService.originBranchHeads(any()) } returns mapOf("master" to "aaa", "idle" to "bbb")
            every { repository.latestPerName() } returns listOf(releaseResult.copy(branch = "master"))
            every { repository.latestFor(any()) } returns null
            every { repository.latestGreenFor(any()) } returns null

            val rows = listing.branches(repo)

            // no bare "master" row next to master@release — it would read as "never built"
            rows.map { it.name } shouldBe listOf("master@release", "idle")
        }

        test("orders main/master first, then flat names, then hierarchical names") {
            every { gitService.originBranchHeads(any()) } returns
                mapOf(
                    "feature/x" to "aaa",
                    "zz-flat" to "bbb",
                    "main" to "ccc",
                    "aa/nested" to "ddd",
                    "develop" to "eee",
                )
            every { repository.latestFor(any()) } returns null
            every { repository.latestGreenFor(any()) } returns null

            listing.branches(repo).map { it.branch } shouldBe
                listOf("main", "develop", "zz-flat", "aa/nested", "feature/x")
        }

        test("joins the latest build and marks never-built branches as unknown with the origin head") {
            every { gitService.originBranchHeads(any()) } returns
                mapOf("main" to "newer-head", "feature/x" to "fedcba98")
            every { repository.latestFor("main") } returns mainResult
            every { repository.latestGreenFor("main") } returns mainResult
            every { repository.latestFor("feature/x") } returns null
            every { repository.latestGreenFor("feature/x") } returns null

            val branches = listing.branches(repo)

            branches[0].branch shouldBe "main"
            branches[0].status shouldBe "success"
            branches[0].commit shouldBe mainResult.commit
            branches[0].artifactKey shouldBe "main-abc123-key"
            branches[0].latestGreenUrl shouldBe "/branches/main"
            branches[1].branch shouldBe "feature/x"
            branches[1].status shouldBe "unknown"
            branches[1].commit shouldBe "fedcba98"
            branches[1].startedAt shouldBe null
            branches[1].artifactKey shouldBe ""
            branches[1].latestGreenUrl shouldBe null
        }

        test("a failed latest build carries no permanent URL — it belongs to the older green build") {
            every { gitService.originBranchHeads(any()) } returns mapOf("feature/x" to "aaa")
            every { repository.latestFor("feature/x") } returns
                mainResult.copy(branch = "feature/x", name = "feature/x", status = BuildStatus.FAILED, artifactKey = "failed-key")
            every { repository.latestGreenFor("feature/x") } returns
                mainResult.copy(branch = "feature/x", name = "feature/x", artifactKey = "green-key")

            val branches = listing.branches(repo)

            branches[0].status shouldBe "failed"
            branches[0].latestGreenUrl shouldBe null
        }

        test("a named slot pool gets its own row right after its branch") {
            val nightly =
                mainResult.copy(name = "main@nightly", status = BuildStatus.FAILED, artifactKey = "nightly-key")
            every { gitService.originBranchHeads(any()) } returns mapOf("main" to "head", "develop" to "d")
            every { repository.latestFor("main") } returns mainResult
            every { repository.latestFor("develop") } returns null
            every { repository.latestGreenFor("main") } returns mainResult
            every { repository.latestGreenFor("main@nightly") } returns null
            every { repository.latestGreenFor("develop") } returns null
            every { repository.latestPerName() } returns listOf(mainResult, nightly)

            val rows = listing.branches(repo)

            rows.map { it.name } shouldBe listOf("main", "main@nightly", "develop")
            rows[1].branch shouldBe "main"
            rows[1].status shouldBe "failed"
            rows[1].artifactKey shouldBe "nightly-key"
            // the branch row keeps its own status and permanent link, untouched by the nightly
            rows[0].status shouldBe "success"
            rows[0].latestGreenUrl shouldBe "/branches/main"
        }
    }
}
