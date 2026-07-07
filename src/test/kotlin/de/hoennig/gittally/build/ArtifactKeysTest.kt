package de.hoennig.gittally.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Paths
import java.time.Instant

class ArtifactKeysTest : FunSpec() {
    private val startedAt = Instant.parse("2026-07-07T10:00:00Z")

    init {
        test("branchKey sanitizes unsafe characters and appends a 12-char hash") {
            ArtifactKeys.branchKey("feature/x") shouldMatch "feature_x-[0-9a-f]{12}"
        }

        test("branches with the same sanitized name get different keys") {
            ArtifactKeys.branchKey("feature/x") shouldNotBe ArtifactKeys.branchKey("feature_x")
        }

        test("buildKey is stable for the same input") {
            ArtifactKeys.buildKey("main", startedAt) shouldBe ArtifactKeys.buildKey("main", startedAt)
        }

        test("buildKey differs per start time") {
            ArtifactKeys.buildKey("main", startedAt) shouldNotBe
                ArtifactKeys.buildKey("main", startedAt.plusSeconds(1))
        }

        test("buildKey contains the branch key and the sanitized start timestamp") {
            val key = ArtifactKeys.buildKey("main", startedAt)

            key shouldContain ArtifactKeys.branchKey("main")
            key shouldContain "2026-07-07T10_00_00Z"
        }

        test("repoKey sanitizes the absolute repository path") {
            ArtifactKeys.repoKey(Paths.get("/home/user/my repo")) shouldBe "_home_user_my_repo"
        }
    }
}
