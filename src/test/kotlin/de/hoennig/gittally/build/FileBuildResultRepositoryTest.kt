package de.hoennig.gittally.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class FileBuildResultRepositoryTest : FunSpec() {
    private val baseTime = Instant.parse("2026-07-07T10:00:00Z")

    private fun newFile(): Path = Files.createTempDirectory("gittally-results-test").resolve("build-results.json")

    private fun result(
        branch: String = "main",
        status: BuildStatus = BuildStatus.SUCCESS,
        startedOffsetSeconds: Long = 0,
        commit: String = "abc1234",
        duration: Duration? = Duration.ofSeconds(90),
        artifactKey: String = "$branch-$startedOffsetSeconds",
    ) = BuildResult(
        branch = branch,
        commit = commit,
        status = status,
        startedAt = baseTime.plusSeconds(startedOffsetSeconds),
        duration = duration,
        artifactKey = artifactKey,
    )

    init {
        test("starts empty when file is missing") {
            val repository = FileBuildResultRepository(newFile())

            repository.history().shouldBeEmpty()
            repository.latestPerName().shouldBeEmpty()
            repository.latestFor("main").shouldBeNull()
        }

        test("append and reload round-trips all fields") {
            val file = newFile()
            val original = result(branch = "feature/x", status = BuildStatus.FAILED, duration = Duration.ofSeconds(61))
            FileBuildResultRepository(file).append(original)

            val reloaded = FileBuildResultRepository(file).history()

            reloaded shouldContainExactly listOf(original)
        }

        test("round-trips a null duration") {
            val file = newFile()
            val original = result(status = BuildStatus.PENDING, duration = null)
            FileBuildResultRepository(file).append(original)

            FileBuildResultRepository(file).history() shouldContainExactly listOf(original)
        }

        test("history returns newest first") {
            val repository = FileBuildResultRepository(newFile())
            val older = result(startedOffsetSeconds = 0)
            val newer = result(startedOffsetSeconds = 60)
            repository.append(older)
            repository.append(newer)

            repository.history() shouldContainExactly listOf(newer, older)
        }

        test("latestFor returns the newest entry of the branch") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", startedOffsetSeconds = 0))
            repository.append(result(branch = "main", startedOffsetSeconds = 60))
            repository.append(result(branch = "other", startedOffsetSeconds = 120))

            repository.latestFor("main") shouldBe result(branch = "main", startedOffsetSeconds = 60)
        }

        test("latestGreenFor returns the newest SUCCESS entry even when newer builds failed") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 60))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120))
            repository.append(result(branch = "other", status = BuildStatus.SUCCESS, startedOffsetSeconds = 180))

            repository.latestGreenFor("main") shouldBe
                result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 60)
        }

        test("latestGreenFor returns null for a branch without a successful build") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.FAILED))

            repository.latestGreenFor("main").shouldBeNull()
            repository.latestGreenFor("unknown").shouldBeNull()
        }

        test("latestPerName returns one entry per build name, newest first") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", startedOffsetSeconds = 0))
            repository.append(result(branch = "main", startedOffsetSeconds = 60))
            repository.append(result(branch = "feature/x", startedOffsetSeconds = 120))

            repository.latestPerName() shouldContainExactly
                listOf(
                    result(branch = "feature/x", startedOffsetSeconds = 120),
                    result(branch = "main", startedOffsetSeconds = 60),
                )
        }

        test("a named result forms its own pool for latest, green, supersession, and retention") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0))
            repository.append(
                result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 10, artifactKey = "nightly-10")
                    .copy(name = "main@nightly"),
            )
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 20))

            repository.latestFor("main")!!.artifactKey shouldBe "main-20"
            repository.latestFor("main@nightly")!!.artifactKey shouldBe "nightly-10"
            // the branch pool's green build does not leak into the nightly pool
            repository.latestGreenFor("main@nightly").shouldBeNull()
            repository.latestPerName().map { it.name } shouldContainExactlyInAnyOrder listOf("main", "main@nightly")

            // retention counts per name: retention 1 keeps the nightly although the branch built more recently
            val removed = repository.prune(listOf("main"), retentionPerBranch = 1)
            removed.map { it.artifactKey } shouldContainExactly listOf("main-0")
            repository.history().map { it.artifactKey } shouldContainExactlyInAnyOrder listOf("main-20", "nightly-10")
        }

        test("prune drops a named pool once its underlying branch is gone from origin") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(
                result(branch = "gone", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0, artifactKey = "nightly-key")
                    .copy(name = "gone@nightly"),
            )

            val removed = repository.prune(listOf("main"), retentionPerBranch = 3)

            removed.map { it.artifactKey } shouldContainExactly listOf("nightly-key")
        }

        test("updateLatest transforms only the newest entry of the branch") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", status = BuildStatus.RUNNING, startedOffsetSeconds = 60))

            val updated = repository.updateLatest("main") { it.copy(status = BuildStatus.SUCCESS) }

            updated shouldBe result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 60)
            repository.history() shouldContainExactly
                listOf(
                    result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 60),
                    result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0),
                )
        }

        test("updateByArtifactKey updates the matching entry even when a newer entry of the branch exists") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.RUNNING, startedOffsetSeconds = 0, artifactKey = "key-a"))
            repository.append(result(branch = "main", status = BuildStatus.PENDING, startedOffsetSeconds = 60, artifactKey = "key-b"))

            val updated = repository.updateByArtifactKey("key-a") { it.copy(status = BuildStatus.SUCCESS) }

            updated shouldBe result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0, artifactKey = "key-a")
            repository.latestFor("main") shouldBe
                result(branch = "main", status = BuildStatus.PENDING, startedOffsetSeconds = 60, artifactKey = "key-b")
        }

        test("updateByArtifactKey returns null for an unknown artifact key") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result())

            repository.updateByArtifactKey("unknown") { it.copy(status = BuildStatus.FAILED) }.shouldBeNull()
        }

        test("updateLatest returns null for an unknown branch") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main"))

            repository.updateLatest("unknown") { it.copy(status = BuildStatus.FAILED) }.shouldBeNull()
        }

        test("delete removes entries by artifact key") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", startedOffsetSeconds = 0, artifactKey = "key-a"))
            repository.append(result(branch = "main", startedOffsetSeconds = 60, artifactKey = "key-b"))

            repository.delete("key-a").shouldBeTrue()

            repository.history() shouldContainExactly
                listOf(result(branch = "main", startedOffsetSeconds = 60, artifactKey = "key-b"))
        }

        test("delete returns false for an unknown artifact key") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result())

            repository.delete("unknown").shouldBeFalse()
        }

        test("markStaleRunningAsInterrupted marks running builds") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.RUNNING))

            val changed = repository.markStaleRunningAsInterrupted()

            changed shouldContainExactly listOf(result(branch = "main", status = BuildStatus.INTERRUPTED))
            repository.latestFor("main")?.status shouldBe BuildStatus.INTERRUPTED
        }

        test("markStaleRunningAsInterrupted marks superseded pending builds") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.PENDING, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", status = BuildStatus.PENDING, startedOffsetSeconds = 60))

            val changed = repository.markStaleRunningAsInterrupted()

            changed shouldContainExactly
                listOf(result(branch = "main", status = BuildStatus.INTERRUPTED, startedOffsetSeconds = 0))
            repository.latestFor("main")?.status shouldBe BuildStatus.PENDING
        }

        test("markStaleRunningAsInterrupted keeps terminal statuses and unrelated branches") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0))
            repository.append(result(branch = "other", status = BuildStatus.FAILED, startedOffsetSeconds = 60))

            repository.markStaleRunningAsInterrupted().shouldBeEmpty()

            repository.history().map { it.status } shouldContainExactly
                listOf(BuildStatus.FAILED, BuildStatus.SUCCESS)
        }

        test("prune keeps only the retention count per branch and returns the removed entries") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", startedOffsetSeconds = 0))
            repository.append(result(branch = "main", startedOffsetSeconds = 60))
            repository.append(result(branch = "main", startedOffsetSeconds = 120))

            val removed = repository.prune(originBranches = listOf("main"), retentionPerBranch = 2)

            removed shouldContainExactly listOf(result(branch = "main", startedOffsetSeconds = 0))
            repository.history() shouldContainExactly
                listOf(
                    result(branch = "main", startedOffsetSeconds = 120),
                    result(branch = "main", startedOffsetSeconds = 60),
                )
        }

        test("prune never removes queued or running results, even of branches gone from origin") {
            val repository = FileBuildResultRepository(newFile())
            // a merged branch, deleted from origin while its last build still runs
            repository.append(result(branch = "merged", status = BuildStatus.FAILED, startedOffsetSeconds = 0))
            repository.append(result(branch = "merged", status = BuildStatus.RUNNING, startedOffsetSeconds = 60))
            // a queued build beyond the retention count of its branch
            repository.append(result(branch = "main", status = BuildStatus.PENDING, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", startedOffsetSeconds = 60))

            val removed =
                repository.prune(
                    originBranches = listOf("main"),
                    retentionPerBranch = 1,
                    retentionCutoff = baseTime.plusSeconds(30),
                )

            removed shouldContainExactly listOf(result(branch = "merged", status = BuildStatus.FAILED, startedOffsetSeconds = 0))
            repository.history() shouldContainExactlyInAnyOrder
                listOf(
                    result(branch = "merged", status = BuildStatus.RUNNING, startedOffsetSeconds = 60),
                    result(branch = "main", status = BuildStatus.PENDING, startedOffsetSeconds = 0),
                    result(branch = "main", startedOffsetSeconds = 60),
                )
        }

        test("prune drops entries older than the retention cutoff even within the retention count") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120))

            val removed =
                repository.prune(
                    originBranches = listOf("main"),
                    retentionPerBranch = 3,
                    retentionCutoff = baseTime.plusSeconds(90),
                )

            removed shouldContainExactlyInAnyOrder
                listOf(
                    result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 0),
                    result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60),
                )
            repository.history() shouldContainExactly
                listOf(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120))
        }

        test("prune never age-prunes a branch's newest entry") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 0))

            val removed =
                repository.prune(
                    originBranches = listOf("main"),
                    retentionPerBranch = 3,
                    retentionCutoff = baseTime.plusSeconds(300),
                )

            removed.shouldBeEmpty()
            repository.history() shouldContainExactly
                listOf(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 0))
        }

        test("prune applies the retention count and cutoff as independent limits") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120))

            val removed =
                repository.prune(
                    originBranches = listOf("main"),
                    // the count drops the entry at 0, the cutoff drops the entry at 60
                    retentionPerBranch = 2,
                    retentionCutoff = baseTime.plusSeconds(90),
                )

            removed shouldContainExactlyInAnyOrder
                listOf(
                    result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 0),
                    result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60),
                )
            repository.history() shouldContainExactly
                listOf(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120))
        }

        test("prune with keepLatestGreen keeps the newest green build beyond the retention cutoff") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120))

            val removed =
                repository.prune(
                    originBranches = listOf("main"),
                    retentionPerBranch = 3,
                    keepLatestGreen = true,
                    retentionCutoff = baseTime.plusSeconds(90),
                )

            removed shouldContainExactly
                listOf(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60))
            repository.history() shouldContainExactly
                listOf(
                    result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120),
                    result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0),
                )
        }

        test("prune with keepLatestGreen keeps the newest green build beyond the retention count") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60))
            repository.append(result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120))

            val removed =
                repository.prune(originBranches = listOf("main"), retentionPerBranch = 2, keepLatestGreen = true)

            removed.shouldBeEmpty()
            repository.history() shouldContainExactly
                listOf(
                    result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 120),
                    result(branch = "main", status = BuildStatus.FAILED, startedOffsetSeconds = 60),
                    result(branch = "main", status = BuildStatus.SUCCESS, startedOffsetSeconds = 0),
                )
        }

        test("prune with keepLatestGreen still drops green builds of branches missing from origin") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "gone", status = BuildStatus.SUCCESS))

            val removed =
                repository.prune(originBranches = listOf("main"), retentionPerBranch = 3, keepLatestGreen = true)

            removed shouldContainExactly listOf(result(branch = "gone", status = BuildStatus.SUCCESS))
            repository.history().shouldBeEmpty()
        }

        test("prune drops entries of branches missing from origin") {
            val repository = FileBuildResultRepository(newFile())
            repository.append(result(branch = "main", startedOffsetSeconds = 0))
            repository.append(result(branch = "gone", startedOffsetSeconds = 60))
            repository.append(result(branch = "gone", startedOffsetSeconds = 120))

            val removed = repository.prune(originBranches = listOf("main"), retentionPerBranch = 3)

            removed shouldContainExactlyInAnyOrder
                listOf(
                    result(branch = "gone", startedOffsetSeconds = 60),
                    result(branch = "gone", startedOffsetSeconds = 120),
                )
            repository.history() shouldContainExactly listOf(result(branch = "main", startedOffsetSeconds = 0))
        }

        test("a corrupt file is treated as empty and can be overwritten") {
            val file = newFile()
            Files.createDirectories(file.parent)
            Files.writeString(file, "this is not json {")
            val repository = FileBuildResultRepository(file)

            repository.history().shouldBeEmpty()

            repository.append(result())
            repository.history() shouldContainExactly listOf(result())
        }

        test("writes leave no temp files behind") {
            val file = newFile()
            val repository = FileBuildResultRepository(file)

            repository.append(result())
            repository.updateLatest("main") { it.copy(status = BuildStatus.FAILED) }

            Files.list(file.parent).use { entries ->
                entries.toList().map { it.fileName.toString() } shouldContainExactly listOf("build-results.json")
            }
        }
    }
}
