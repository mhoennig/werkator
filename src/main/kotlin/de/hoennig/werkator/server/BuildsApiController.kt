package de.hoennig.werkator.server

import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.config.BuildDefinition
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoLinks
import de.hoennig.werkator.repo.RepoRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * JSON API over build results and running builds, replacing the legacy
 * `/control/…` endpoints. Mutating endpoints are guarded by the control token,
 * like the legacy cancel token — in the header [TOKEN_HEADER] only: a token in
 * the query string would end up in access logs, browser history and `Referer`
 * headers, and it never expires.
 */
@RestController
class BuildsApiController(
    private val buildExecutor: BuildExecutor,
    private val controlTokens: ControlTokenService,
    private val gitService: GitService,
    private val branchListing: BranchListing,
    private val registry: RepoRegistry,
    private val repoLinks: RepoLinks,
) {
    /**
     * Every route exists twice: repository-scoped (`/api/repos/<name>/…`) and unscoped.
     * The unscoped form means the served repository ([RepoRegistry.current]) and stays
     * for good — bookmarks, the legacy UI, and the links already posted to Gitea were
     * written without a repository segment, and a CI that breaks its own old links is
     * a CI nobody trusts.
     */
    private fun repoOf(name: String?): RepoContext =
        if (name == null) registry.current() else registry.byName(name) ?: throw UnknownRepositoryException(name)

    private fun RepoContext.isLatestGreen(result: BuildResult): Boolean =
        results.latestGreenFor(result.name)?.artifactKey == result.artifactKey

    /** The prefix the permanent links in the answers carry; empty with one served repository. */
    private fun uiBase(repo: RepoContext): String = repoLinks.base(repo)

    /** An unknown repository name answers like every other miss of this API: 404 with `error`. */
    @ExceptionHandler(UnknownRepositoryException::class)
    fun unknownRepository(e: UnknownRepositoryException): ResponseEntity<Any> = notFound(e.message ?: "unknown repository")

    @GetMapping("/api/builds/latest", "/api/repos/{repo}/builds/latest")
    fun latest(
        @PathVariable(name = "repo", required = false) repoName: String?,
    ): List<BuildResultDto> {
        val repo = repoOf(repoName)
        return repo.results.latestPerName().map { BuildResultDto.from(it, repo.isLatestGreen(it), uiBase(repo)) }
    }

    /** The legacy branches view: every origin branch with its latest build or `unknown`. */
    @GetMapping("/api/branches", "/api/repos/{repo}/branches")
    fun branches(
        @PathVariable(name = "repo", required = false) repoName: String?,
    ): List<BranchDto> {
        val repo = repoOf(repoName)
        return branchListing.branches(repo, uiBase(repo))
    }

    @GetMapping("/api/builds/history", "/api/repos/{repo}/builds/history")
    fun history(
        @PathVariable(name = "repo", required = false) repoName: String?,
    ): List<BuildResultDto> {
        val repo = repoOf(repoName)
        return repo.results.history().map { BuildResultDto.from(it, repo.isLatestGreen(it), uiBase(repo)) }
    }

    /**
     * The currently executing builds of the served repository — several are possible,
     * up to `executor.maxConcurrent`. The executor is instance-global and returns the
     * builds of every registered repository, so this view filters: its [repository]
     * holds only this repository's results, and a foreign build looked up in them
     * would fall back to RUNNING and show a status nobody recorded.
     */
    @GetMapping("/api/builds/current", "/api/repos/{repo}/builds/current")
    fun current(
        @PathVariable(name = "repo", required = false) repoName: String?,
    ): List<CurrentBuildDto> {
        val repo = repoOf(repoName)
        val results = repo.results.history()
        return buildExecutor.currentBuilds().filter { it.repo === repo }.map { build ->
            CurrentBuildDto(
                branch = build.branch,
                name = build.name,
                commit = build.commit,
                artifactKey = build.artifactKey,
                status =
                    (results.firstOrNull { it.artifactKey == build.artifactKey }?.status ?: BuildStatus.RUNNING)
                        .jsonName,
                startedAt = build.startedAt,
                runningSince = build.runningSince,
                logSize = liveLogSize(build.liveLogFile),
            )
        }
    }

    /** Incremental live-log fetch of one running build; poll again with `offset = nextOffset`. */
    @GetMapping("/api/builds/current/{artifactKey}/log", "/api/repos/{repo}/builds/current/{artifactKey}/log")
    fun currentLog(
        @PathVariable(name = "repo", required = false) repoName: String?,
        @PathVariable artifactKey: String,
        @RequestParam(defaultValue = "0") offset: Long,
    ): ResponseEntity<Any> {
        val repo = repoOf(repoName)
        val build =
            buildExecutor.currentBuilds().firstOrNull { it.repo === repo && it.artifactKey == artifactKey }
                ?: return notFound("no running build with artifact key '$artifactKey'")
        return ResponseEntity.ok(readLogTail(artifactKey, build.liveLogFile, offset))
    }

    /**
     * Re-enqueues the build name [branch] — or the origin head for a branch never
     * built, so the branches view can trigger first builds like legacy. A restarted
     * build re-runs its recorded build definition, with the settings from the current
     * configuration.
     *
     * With [atOriginHead] the branch's current origin head is built instead of the
     * recorded commit. That is what the branches view asks for: a row there stands for
     * a branch, not for a past run, and repeating an overtaken commit answers a
     * question nobody asked — a build gate comparing against origin cannot even pass
     * on it. Latest and history mean the recorded run, and keep repeating it.
     *
     * The name is a parameter, not a path variable, because branch names may contain
     * slashes (Tomcat rejects encoded slashes in the path by default).
     */
    @PostMapping("/api/builds/restart", "/api/repos/{repo}/builds/restart")
    fun restart(
        @PathVariable(name = "repo", required = false) repoName: String?,
        @RequestParam branch: String,
        @RequestParam(defaultValue = "false") atOriginHead: Boolean,
        @RequestHeader(name = TOKEN_HEADER, required = false) headerToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken)?.let { return it }
        val repo = repoOf(repoName)
        val workingDir = repo.workingDir
        val latest = repo.results.latestFor(branch)
        // the name may be a pool like `main@pitest`; the branch to build is the recorded one
        val branchName = latest?.branch ?: branch
        val commit =
            if (atOriginHead) {
                gitService.originHeadCommit(branchName, workingDir)
                    ?: return notFound("branch '$branchName' is not on origin")
            } else {
                latest?.commit
                    ?: gitService.originHeadCommit(branchName, workingDir)
                    ?: return notFound("branch '$branch' has no recorded build and no origin counterpart")
            }
        // a restarted build re-runs its recorded build definition (settings from the current config)
        val running =
            buildExecutor.startBuild(
                repo = repo,
                branch = branchName,
                commit = commit,
                build = latest?.build ?: BuildDefinition.DEFAULT,
            )
        return ResponseEntity.accepted().body(
            BuildResultDto(
                branch = running.branch,
                name = running.name,
                commit = running.commit,
                status = BuildStatus.PENDING.jsonName,
                startedAt = running.startedAt,
                durationSeconds = null,
                artifactKey = running.artifactKey,
            ),
        )
    }

    /** Cancels by artifact key because multiple builds can run concurrently. */
    @PostMapping("/api/builds/{artifactKey}/cancel", "/api/repos/{repo}/builds/{artifactKey}/cancel")
    fun cancel(
        @PathVariable(name = "repo", required = false) repoName: String?,
        @PathVariable artifactKey: String,
        @RequestHeader(name = TOKEN_HEADER, required = false) headerToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken)?.let { return it }
        val repo = repoOf(repoName)
        // the executor cancels by key across all repositories; a route that names a
        // repository must not reach into another one, and a queued or running build
        // always has its PENDING/RUNNING result recorded in its own repository
        if (repo.results.history().none { it.artifactKey == artifactKey }) {
            return notFound("no queued or running build with artifact key '$artifactKey'")
        }
        if (!buildExecutor.cancel(artifactKey)) {
            return notFound("no queued or running build with artifact key '$artifactKey'")
        }
        return ResponseEntity.accepted().body(mapOf("cancelled" to artifactKey))
    }

    /** Removes the stored result and its artifact directory, like the legacy `/control/delete`. */
    @DeleteMapping("/api/builds/{artifactKey}", "/api/repos/{repo}/builds/{artifactKey}")
    fun delete(
        @PathVariable(name = "repo", required = false) repoName: String?,
        @PathVariable artifactKey: String,
        @RequestHeader(name = TOKEN_HEADER, required = false) headerToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken)?.let { return it }
        val repo = repoOf(repoName)
        if (!repo.results.delete(artifactKey)) {
            return notFound("no build with artifact key '$artifactKey'")
        }
        repo.artifactStore.prune(repo.results.history())
        return ResponseEntity.ok(mapOf("deleted" to artifactKey))
    }

    private fun rejectBadToken(submittedToken: String?): ResponseEntity<Any>? =
        if (controlTokens.matches(submittedToken)) {
            null
        } else {
            ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "missing or wrong control token"))
        }

    private fun notFound(message: String): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to message))

    private fun liveLogSize(liveLogFile: Path): Long = if (Files.isRegularFile(liveLogFile)) Files.size(liveLogFile) else 0L

    /** At most [MAX_LOG_CHUNK] bytes per response; a chunk may split a multi-byte character. */
    private fun readLogTail(
        artifactKey: String,
        liveLogFile: Path,
        requestedOffset: Long,
    ): LogTailDto {
        if (!Files.isRegularFile(liveLogFile)) {
            return LogTailDto(artifactKey, offset = 0, nextOffset = 0, content = "")
        }
        val size = Files.size(liveLogFile)
        val offset = requestedOffset.coerceIn(0L, size)
        val length = (size - offset).coerceAtMost(MAX_LOG_CHUNK).toInt()
        if (length == 0) {
            return LogTailDto(artifactKey, offset = offset, nextOffset = size, content = "")
        }
        FileChannel.open(liveLogFile, StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep reading until the requested chunk is complete or the file ends
            }
            buffer.flip()
            val content = String(buffer.array(), 0, buffer.limit(), Charsets.UTF_8)
            return LogTailDto(artifactKey, offset = offset, nextOffset = offset + buffer.limit(), content = content)
        }
    }

    companion object {
        const val TOKEN_HEADER = "X-werkator-Token"
        private const val MAX_LOG_CHUNK = 1024L * 1024L
    }
}
