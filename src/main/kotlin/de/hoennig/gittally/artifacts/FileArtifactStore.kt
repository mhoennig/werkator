package de.hoennig.gittally.artifacts

import de.hoennig.gittally.build.ArtifactKeys
import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.config.BranchConfig
import de.hoennig.gittally.config.ConfigLoader
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Stores build artifacts on the filesystem under `<root>/branches/<artifactKey>/`.
 * The root is `artifacts.rootDir` from the config, or the platform default
 * `XDG_STATE_HOME` (falling back to `~/.local/state`) plus `/gittally/artifacts/<repo-key>` when unset —
 * deliberately not `/tmp` like legacy, where artifacts vanished on reboot.
 *
 * Each build is assembled in a temporary directory next to its target and moved
 * into place atomically, so a crashed persist never leaves a partial artifact dir.
 * Builds persist concurrently (their keys are unique) under a shared read lock;
 * [prune] takes the write lock so it never deletes a directory mid-persist.
 */
class FileArtifactStore(
    private val configLoader: ConfigLoader,
    private val workingDir: Path = Paths.get("."),
    private val env: (String) -> String? = System::getenv,
) : ArtifactStore {
    private val log = LoggerFactory.getLogger(FileArtifactStore::class.java)

    private val lock = ReentrantReadWriteLock()

    override fun persist(
        build: BuildResult,
        stagingDir: Path,
        workspace: Path?,
    ) {
        lock.read {
            val branchesDir = Files.createDirectories(branchesDir())
            val target = branchesDir.resolve(build.artifactKey)
            val incoming = branchesDir.resolve(INCOMING_PREFIX + build.artifactKey)
            try {
                deleteRecursively(incoming)
                Files.createDirectories(incoming)
                copyChildren(stagingDir, incoming)
                copyArtifactDirs(build, workspace, incoming)
                deleteRecursively(target)
                Files.move(incoming, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                deleteRecursively(incoming)
                throw e
            }
            deleteRecursively(stagingDir)
            log.info("persisted build artifacts of {} to {}", build.artifactKey, target)
        }
    }

    override fun prune(keptResults: Collection<BuildResult>): List<String> {
        lock.write {
            val branchesDir = branchesDir()
            if (!Files.isDirectory(branchesDir, LinkOption.NOFOLLOW_LINKS)) {
                return emptyList()
            }
            val keptKeys = keptResults.map { it.artifactKey }.toSet()
            val removed = mutableListOf<String>()
            Files.list(branchesDir).use { children ->
                children.forEach { child ->
                    val key = child.fileName.toString()
                    if (key !in keptKeys) {
                        deleteRecursively(child)
                        removed += key
                    }
                }
            }
            if (removed.isNotEmpty()) {
                log.info("pruned {} artifact dir(s): {}", removed.size, removed)
            }
            return removed
        }
    }

    override fun artifactDir(artifactKey: String): Path? {
        if (!ARTIFACT_KEY_PATTERN.matches(artifactKey)) {
            return null
        }
        val branchesDir = branchesDir()
        val dir = branchesDir.resolve(artifactKey).normalize()
        if (dir.parent != branchesDir) {
            return null
        }
        return dir.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun branchesDir(): Path = rootDir().resolve("branches")

    override fun rootDir(): Path {
        val configured =
            configLoader
                .load(workingDir)
                .artifacts.rootDir
                .trim()
        if (configured.isNotEmpty()) {
            return workingDir.resolve(expandHome(configured)).toAbsolutePath().normalize()
        }
        val stateHome =
            env("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".local", "state")
        return stateHome
            .resolve("gittally")
            .resolve("artifacts")
            .resolve(ArtifactKeys.repoKey(workingDir))
            .toAbsolutePath()
            .normalize()
    }

    private fun expandHome(path: String): Path =
        if (path == "~" || path.startsWith("~/")) {
            Paths.get(System.getProperty("user.home"), path.removePrefix("~"))
        } else {
            Paths.get(path)
        }

    private fun copyArtifactDirs(
        build: BuildResult,
        workspace: Path?,
        targetDir: Path,
    ) {
        if (workspace == null) {
            log.warn("build {} has no workspace; storing only its logs", build.artifactKey)
            return
        }
        for (artifactDir in branchConfig(build.branch).artifactDirs) {
            if (artifactDir.isBlank()) {
                continue
            }
            val source = workspace.resolve(artifactDir)
            if (!Files.isDirectory(source)) {
                log.info("build {} did not produce artifact directory {}; skipped", build.artifactKey, artifactDir)
                continue
            }
            copyRecursively(source, targetDir.resolve(archivedPath(artifactDir)))
        }
    }

    /** Legacy `archived_artefact_dir_path`: `build/reports` archives as `reports/`, everything else below `reports/<dir>`. */
    private fun archivedPath(artifactDir: String): String =
        if (artifactDir == "build/reports") {
            "reports"
        } else {
            "reports/$artifactDir"
        }

    private fun branchConfig(branch: String): BranchConfig {
        val branches = configLoader.load(workingDir).branches
        return branches[branch] ?: branches["default"] ?: BranchConfig()
    }

    private fun copyChildren(
        sourceDir: Path,
        targetDir: Path,
    ) {
        if (Files.isDirectory(sourceDir)) {
            copyRecursively(sourceDir, targetDir)
        }
    }

    private fun copyRecursively(
        source: Path,
        target: Path,
    ) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Does not follow symlinks — a link is deleted, its target is never touched. */
    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) {
                        throw exc
                    }
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    companion object {
        /** Work-in-progress dir next to the target; unreferenced leftovers are cleaned up by [prune]. */
        private const val INCOMING_PREFIX = ".incoming-"

        /** Same character set the key sanitization produces; everything else is rejected on lookup. */
        private val ARTIFACT_KEY_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}
