package de.hoennig.gittally.server

import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.git.GitService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant

class BranchListingTest : FunSpec() {
    private val gitService = mockk<GitService>()
    private val repository = mockk<BuildResultRepository>()
    private val listing = BranchListing(gitService, repository)

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

            listing.branches().map { it.branch } shouldBe
                listOf("main", "develop", "zz-flat", "aa/nested", "feature/x")
        }

        test("joins the latest build and marks never-built branches as unknown with the origin head") {
            every { gitService.originBranchHeads(any()) } returns
                mapOf("main" to "newer-head", "feature/x" to "fedcba98")
            every { repository.latestFor("main") } returns mainResult
            every { repository.latestFor("feature/x") } returns null

            val branches = listing.branches()

            branches[0].branch shouldBe "main"
            branches[0].status shouldBe "success"
            branches[0].commit shouldBe mainResult.commit
            branches[0].artifactKey shouldBe "main-abc123-key"
            branches[1].branch shouldBe "feature/x"
            branches[1].status shouldBe "unknown"
            branches[1].commit shouldBe "fedcba98"
            branches[1].startedAt shouldBe null
            branches[1].artifactKey shouldBe ""
        }
    }
}
