package de.hoennig.gittally.server

import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.gitea.GiteaClient
import de.hoennig.gittally.gitea.GiteaStatusResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Replaces the legacy `/control/status` proxy. The response always answers with
 * HTTP 200 and an explicit status — a Gitea failure becomes `giteaError` plus the
 * local fallback (or `unknown`), never a hanging request (the [GiteaClient]
 * requests time out).
 */
@RestController
class StatusApiController(
    private val repository: BuildResultRepository,
    private val giteaClient: GiteaClient,
) {
    @GetMapping("/api/status/{commit}")
    fun status(
        @PathVariable commit: String,
    ): ResponseEntity<Any> {
        if (!COMMIT_PATTERN.matches(commit)) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "'$commit' is not an abbreviated or full commit hash"))
        }
        val localStatus =
            repository
                .history()
                .firstOrNull { it.commit.startsWith(commit, ignoreCase = true) }
                ?.status
        val giteaResult = giteaClient.readStatus(commit)
        val giteaStatus = (giteaResult as? GiteaStatusResult.Found)?.status
        val effective = giteaStatus ?: localStatus
        return ResponseEntity.ok(
            CommitStatusDto(
                commit = commit,
                status = effective?.jsonName ?: CommitStatusDto.UNKNOWN_STATUS,
                localStatus = localStatus?.jsonName,
                giteaStatus = giteaStatus?.jsonName,
                giteaError = (giteaResult as? GiteaStatusResult.Error)?.message,
            ),
        )
    }

    companion object {
        /** Like the legacy handler: 7 to 40 hex digits. */
        private val COMMIT_PATTERN = Regex("[0-9a-fA-F]{7,40}")
    }
}
