package de.hoennig.werkator.artifacts

import de.hoennig.werkator.build.ArtifactKeys
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.config.ConfigLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.io.path.listDirectoryEntries

class FileArtifactStoreTest : FunSpec() {
    private val startedAt = Instant.parse("2026-07-07T10:00:00Z")

    private class Harness {
        val workingDir: Path = Files.createTempDirectory("werkator-store-test")
        val root: Path = workingDir.resolve("artifact-root")
        val store = FileArtifactStore(ConfigLoader(), workingDir)

        init {
            Files.writeString(
                workingDir.resolve(".werkator.yml"),
                """
                artifacts:
                  rootDir: "$root"
                branches:
                  default:
                    artifactDirs:
                      - build/reports
                      - build/doc
                """.trimIndent(),
            )
        }

        fun branchesDir(): Path = root.resolve("branches")
    }

    private fun buildResult(
        branch: String = "main",
        startedAt: Instant = this.startedAt,
    ) = BuildResult(
        branch = branch,
        commit = "abc123",
        status = BuildStatus.SUCCESS,
        startedAt = startedAt,
        duration = Duration.ofSeconds(10),
        artifactKey = ArtifactKeys.buildKey(branch, startedAt),
    )

    private fun stagingDir(): Path {
        val dir = Files.createTempDirectory("werkator-staging-test")
        Files.writeString(dir.resolve("build.stdout.log"), "out")
        Files.writeString(dir.resolve("build.stderr.log"), "err")
        Files.writeString(dir.resolve("build.log"), "live")
        return dir
    }

    private fun workspace(
        h: Harness,
        vararg artifactFiles: String,
    ): Path {
        val workspace = Files.createDirectories(h.workingDir.resolve("workspace"))
        for (file in artifactFiles) {
            val path = workspace.resolve(file)
            Files.createDirectories(path.parent)
            Files.writeString(path, "content of $file")
        }
        return workspace
    }

    init {
        test("persist stores logs and configured artifact directories under the artifact key") {
            val h = Harness()
            val build = buildResult()
            val staging = stagingDir()
            val workspace = workspace(h, "build/reports/tests/index.html", "build/doc/readme.txt")

            h.store.persist(build, staging, workspace)

            val artifactDir = h.branchesDir().resolve(build.artifactKey)
            Files.readString(artifactDir.resolve("build.stdout.log")) shouldBe "out"
            Files.readString(artifactDir.resolve("build.stderr.log")) shouldBe "err"
            Files.readString(artifactDir.resolve("build.log")) shouldBe "live"
            // build/reports archives as reports/ (the artifact page's browsable
            // anchor), every other dir at its own workspace-relative path
            Files.exists(artifactDir.resolve("reports/tests/index.html")) shouldBe true
            Files.exists(artifactDir.resolve("build/doc/readme.txt")) shouldBe true
            Files.exists(artifactDir.resolve("reports/build")) shouldBe false
            Files.exists(staging) shouldBe false
        }

        test("a missing artifact directory is skipped") {
            val h = Harness()
            val build = buildResult()
            val workspace = workspace(h, "build/doc/readme.txt") // no build/reports

            h.store.persist(build, stagingDir(), workspace)

            val artifactDir = h.branchesDir().resolve(build.artifactKey)
            Files.exists(artifactDir.resolve("build/doc/readme.txt")) shouldBe true
            Files.exists(artifactDir.resolve("reports")) shouldBe false
        }

        test("persist without a workspace stores only the logs") {
            val h = Harness()
            val build = buildResult()

            h.store.persist(build, stagingDir(), workspace = null)

            val artifactDir = h.branchesDir().resolve(build.artifactKey)
            Files.readString(artifactDir.resolve("build.log")) shouldBe "live"
            Files.exists(artifactDir.resolve("reports")) shouldBe false
        }

        test("a failing persist leaves no partial artifact directory") {
            val h = Harness()
            val build = buildResult()
            val workspace = workspace(h, "build/reports/secret.txt")
            Files.setPosixFilePermissions(workspace.resolve("build/reports/secret.txt"), emptySet())

            shouldThrow<IOException> {
                h.store.persist(build, stagingDir(), workspace)
            }

            h.branchesDir().listDirectoryEntries() shouldBe emptyList()
        }

        test("the artifact root defaults to XDG_STATE_HOME plus the repo key") {
            val workingDir = Files.createTempDirectory("werkator-store-test")
            val stateHome = Files.createTempDirectory("werkator-state-home-test")
            val store =
                FileArtifactStore(ConfigLoader(), workingDir) { name ->
                    if (name == "XDG_STATE_HOME") stateHome.toString() else null
                }
            val build = buildResult()

            store.persist(build, stagingDir(), workspace = null)

            val expectedDir =
                stateHome
                    .resolve("werkator/artifacts")
                    .resolve(ArtifactKeys.repoKey(workingDir))
                    .resolve("branches")
                    .resolve(build.artifactKey)
            Files.readString(expectedDir.resolve("build.log")) shouldBe "live"
        }

        test("prune deletes exactly the unreferenced artifact directories") {
            val h = Harness()
            val kept = buildResult(branch = "main")
            val alsoKept = buildResult(branch = "feature/x")
            val dropped = buildResult(branch = "main", startedAt = startedAt.plusSeconds(60))
            listOf(kept, alsoKept, dropped).forEach {
                Files.createDirectories(h.branchesDir().resolve(it.artifactKey))
            }

            val removed = h.store.prune(listOf(kept, alsoKept))

            removed shouldContainExactly listOf(dropped.artifactKey)
            Files.exists(h.branchesDir().resolve(kept.artifactKey)) shouldBe true
            Files.exists(h.branchesDir().resolve(alsoKept.artifactKey)) shouldBe true
            Files.exists(h.branchesDir().resolve(dropped.artifactKey)) shouldBe false
        }

        test("prune removes leftover incoming directories of crashed persists") {
            val h = Harness()
            val leftover = h.branchesDir().resolve(".incoming-${buildResult().artifactKey}")
            Files.createDirectories(leftover)
            Files.writeString(leftover.resolve("build.log"), "partial")

            val removed = h.store.prune(emptyList())

            removed shouldContainExactly listOf(leftover.fileName.toString())
            Files.exists(leftover) shouldBe false
        }

        test("prune deletes a symlink without touching its target outside the root") {
            val h = Harness()
            val outside = Files.createTempDirectory("werkator-outside-test")
            Files.writeString(outside.resolve("keep-me.txt"), "precious")
            Files.createDirectories(h.branchesDir())
            val link = h.branchesDir().resolve("evil-link")
            Files.createSymbolicLink(link, outside)

            val removed = h.store.prune(emptyList())

            removed shouldContainExactly listOf("evil-link")
            Files.exists(link) shouldBe false
            Files.readString(outside.resolve("keep-me.txt")) shouldBe "precious"
        }

        test("artifactDir returns the stored directory and rejects unknown or unsafe keys") {
            val h = Harness()
            val build = buildResult()
            h.store.persist(build, stagingDir(), workspace = null)

            h.store.artifactDir(build.artifactKey).shouldNotBeNull() shouldBe
                h.branchesDir().resolve(build.artifactKey)
            h.store.artifactDir("unknown-key").shouldBeNull()
            h.store.artifactDir("..").shouldBeNull()
            h.store.artifactDir("../secrets").shouldBeNull()
        }

        test("concurrent persists and a prune do not interfere") {
            val h = Harness()
            val builds = (1..8).map { buildResult(startedAt = startedAt.plusSeconds(it.toLong())) }
            val stagings = builds.associateWith { stagingDir() }

            val persists =
                builds.map { build ->
                    thread { h.store.persist(build, stagings.getValue(build), workspace = null) }
                }
            val prune = thread { h.store.prune(builds) }
            (persists + prune).forEach { it.join() }

            builds.forEach { build ->
                Files.readString(h.branchesDir().resolve(build.artifactKey).resolve("build.log")) shouldBe "live"
            }
        }
    }
}
