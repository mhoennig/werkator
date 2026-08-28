package de.hoennig.gittally.server

import de.hoennig.gittally.build.ArtifactKeys
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant

class BranchPermalinksTest : FunSpec() {
    private val repository = mockk<BuildResultRepository>()
    private val permalinks = BranchPermalinks(repository)

    private fun result(
        branch: String,
        status: BuildStatus = BuildStatus.SUCCESS,
    ) = BuildResult(
        branch = branch,
        commit = "0123456789abcdef",
        status = status,
        startedAt = Instant.parse("2026-07-07T10:00:00Z"),
        duration = Duration.ofSeconds(83),
        artifactKey = "$branch-key",
    )

    init {
        test("resolves the hash-free permanent key to the branch's latest green build") {
            every { repository.latestPerName() } returns listOf(result("feature/x"), result("main"))
            every { repository.latestGreenFor("feature/x") } returns result("feature/x")

            permalinks.latestGreenBuild("feature_x") shouldBe result("feature/x")
        }

        test("resolves the full branch key with hash suffix") {
            every { repository.latestPerName() } returns listOf(result("feature/x"))
            every { repository.latestGreenFor("feature/x") } returns result("feature/x")

            permalinks.latestGreenBuild(ArtifactKeys.branchKey("feature/x")) shouldBe result("feature/x")
        }

        test("an unknown branch key answers 404") {
            every { repository.latestPerName() } returns listOf(result("main"))

            val exception = shouldThrow<ResponseStatusException> { permalinks.latestGreenBuild("gone") }

            exception.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        test("a branch without a green build answers 404") {
            every { repository.latestPerName() } returns listOf(result("main", status = BuildStatus.FAILED))
            every { repository.latestGreenFor("main") } returns null

            val exception = shouldThrow<ResponseStatusException> { permalinks.latestGreenBuild("main") }

            exception.statusCode shouldBe HttpStatus.NOT_FOUND
        }

        test("a permanent key matching several branches answers 409 and names the candidates") {
            every { repository.latestPerName() } returns listOf(result("feature/x"), result("feature_x"))

            val exception = shouldThrow<ResponseStatusException> { permalinks.latestGreenBuild("feature_x") }

            exception.statusCode shouldBe HttpStatus.CONFLICT
            exception.reason.orEmpty() shouldContain "feature/x"
        }

        test("with ambiguous permanent keys the full branch key still resolves") {
            every { repository.latestPerName() } returns listOf(result("feature/x"), result("feature_x"))
            every { repository.latestGreenFor("feature/x") } returns result("feature/x")

            permalinks.latestGreenBuild(ArtifactKeys.branchKey("feature/x")) shouldBe result("feature/x")
        }

        test("permanentUrl uses the hash-free branch key") {
            BranchPermalinks.permanentUrl("feature/x") shouldBe "/branches/feature_x"
        }

        test("resolves a named slot pool to its own latest green build") {
            val nightly = result("main").copy(name = "main@nightly", artifactKey = "nightly-key")
            every { repository.latestPerName() } returns listOf(result("main"), nightly)
            every { repository.latestGreenFor("main@nightly") } returns nightly

            // sanitized like any branch key: the '@' becomes '_' in the URL
            permalinks.latestGreenBuild("main_nightly") shouldBe nightly
        }
    }
}
