package de.hoennig.werkator.server

import de.hoennig.werkator.build.ArtifactStore
import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildResultRepository
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.config.BuildDefinition
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.repo.RepoContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
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
    private val repository: BuildResultRepository,
    private val buildExecutor: BuildExecutor,
    private val artifactStore: ArtifactStore,
    private val controlTokens: ControlTokenService,
    private val gitService: GitService,
    private val branchListing: BranchListing,
    private val repo: RepoContext,
) {
    private val workingDir: Path
        get() = repo.workingDir

    @GetMapping("/api/builds/latest")
    fun latest(): List<BuildResultDto> = repository.latestPerName().map { BuildResultDto.from(it, it.isLatestGreen()) }

    /** The legacy branches view: every origin branch with its latest build or `unknown`. */
    @GetMapping("/api/branches")
    fun branches(): List<BranchDto> = branchListing.branches(workingDir)

    @GetMapping("/api/builds/history")
    fun history(): List<BuildResultDto> = repository.history().map { BuildResultDto.from(it, it.isLatestGreen()) }

    private fun BuildResult.isLatestGreen(): Boolean = repository.latestGreenFor(name)?.artifactKey == artifactKey

    /** The currently executing builds — several are possible, up to `executor.maxConcurrent`. */
    @GetMapping("/api/builds/current")
    fun current(): List<CurrentBuildDto> {
        val results = repository.history()
        return buildExecutor.currentBuilds().map { build ->
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
    @GetMapping("/api/builds/current/{artifactKey}/log")
    fun currentLog(
        @PathVariable artifactKey: String,
        @RequestParam(defaultValue = "0") offset: Long,
    ): ResponseEntity<Any> {
        val build =
            buildExecutor.currentBuilds().firstOrNull { it.artifactKey == artifactKey }
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
    @PostMapping("/api/builds/restart")
    fun restart(
        @RequestParam branch: String,
        @RequestParam(defaultValue = "false") atOriginHead: Boolean,
        @RequestHeader(name = TOKEN_HEADER, required = false) headerToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken)?.let { return it }
        val latest = repository.latestFor(branch)
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
    @PostMapping("/api/builds/{artifactKey}/cancel")
    fun cancel(
        @PathVariable artifactKey: String,
        @RequestHeader(name = TOKEN_HEADER, required = false) headerToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken)?.let { return it }
        if (!buildExecutor.cancel(artifactKey)) {
            return notFound("no queued or running build with artifact key '$artifactKey'")
        }
        return ResponseEntity.accepted().body(mapOf("cancelled" to artifactKey))
    }

    /** Removes the stored result and its artifact directory, like the legacy `/control/delete`. */
    @DeleteMapping("/api/builds/{artifactKey}")
    fun delete(
        @PathVariable artifactKey: String,
        @RequestHeader(name = TOKEN_HEADER, required = false) headerToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken)?.let { return it }
        if (!repository.delete(artifactKey)) {
            return notFound("no build with artifact key '$artifactKey'")
        }
        artifactStore.prune(repository.history())
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
