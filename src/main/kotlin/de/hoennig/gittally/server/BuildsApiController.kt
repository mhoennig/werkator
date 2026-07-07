package de.hoennig.gittally.server

import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildExecutor
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.git.GitService
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
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * JSON API over build results and running builds, replacing the legacy
 * `/control/…` endpoints. Mutating endpoints are guarded by the control token
 * (header [TOKEN_HEADER] or parameter `token`), like the legacy cancel token.
 */
@RestController
class BuildsApiController(
    private val repository: BuildResultRepository,
    private val buildExecutor: BuildExecutor,
    private val artifactStore: ArtifactStore,
    private val controlTokens: ControlTokenService,
    private val gitService: GitService,
    private val branchListing: BranchListing,
) {
    var workingDir: Path = Paths.get(".")

    @GetMapping("/api/builds/latest")
    fun latest(): List<BuildResultDto> = repository.latestPerBranch().map { BuildResultDto.from(it) }

    /** The legacy branches view: every origin branch with its latest build or `unknown`. */
    @GetMapping("/api/branches")
    fun branches(): List<BranchDto> = branchListing.branches(workingDir)

    @GetMapping("/api/builds/history")
    fun history(): List<BuildResultDto> = repository.history().map { BuildResultDto.from(it) }

    /** The currently executing builds — several are possible, up to `builds.maxConcurrent`. */
    @GetMapping("/api/builds/current")
    fun current(): List<CurrentBuildDto> {
        val results = repository.history()
        return buildExecutor.currentBuilds().map { build ->
            CurrentBuildDto(
                branch = build.branch,
                commit = build.commit,
                artifactKey = build.artifactKey,
                status =
                    (results.firstOrNull { it.artifactKey == build.artifactKey }?.status ?: BuildStatus.RUNNING)
                        .jsonName,
                startedAt = build.startedAt,
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
     * Re-enqueues the branch's last recorded commit — or its origin head for a branch
     * never built, so the branches view can trigger first builds like legacy.
     * The branch is a parameter, not a path variable, because branch names may contain
     * slashes (Tomcat rejects encoded slashes in the path by default).
     */
    @PostMapping("/api/builds/restart")
    fun restart(
        @RequestParam branch: String,
        @RequestHeader(name = TOKEN_HEADER, required = false) headerToken: String?,
        @RequestParam(name = "token", required = false) paramToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken ?: paramToken)?.let { return it }
        val commit =
            repository.latestFor(branch)?.commit
                ?: gitService.originHeadCommit(branch, workingDir)
                ?: return notFound("branch '$branch' has no recorded build and no origin counterpart")
        val running = buildExecutor.startBuild(branch, commit)
        return ResponseEntity.accepted().body(
            BuildResultDto(
                branch = running.branch,
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
        @RequestParam(name = "token", required = false) paramToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken ?: paramToken)?.let { return it }
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
        @RequestParam(name = "token", required = false) paramToken: String?,
    ): ResponseEntity<Any> {
        rejectBadToken(headerToken ?: paramToken)?.let { return it }
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
        const val TOKEN_HEADER = "X-GitTally-Token"
        private const val MAX_LOG_CHUNK = 1024L * 1024L
    }
}
