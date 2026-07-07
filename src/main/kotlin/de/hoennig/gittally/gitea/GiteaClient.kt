package de.hoennig.gittally.gitea

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.config.GitTallyConfig
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.nio.file.Path
import java.nio.file.Paths

/** Outcome of [GiteaClient.readStatus]; errors are values, never exceptions. */
sealed interface GiteaStatusResult {
    /** The newest status matching the configured context. */
    data class Found(
        val status: BuildStatus,
    ) : GiteaStatusResult

    /** Gitea answered, but no status matches the configured context. */
    data object None : GiteaStatusResult

    /** The client is not configured, see [GiteaClient.isEnabled]. */
    data object Disabled : GiteaStatusResult

    /** Gitea is unreachable, answered with an error, or sent an unusable response. */
    data class Error(
        val message: String,
    ) : GiteaStatusResult
}

/**
 * Client for the Gitea commit-status API.
 * All methods are non-fatal: when Gitea is unconfigured, unreachable, or failing,
 * they log and return a fallback value instead of throwing.
 */
@Service
class GiteaClient(
    private val configLoader: ConfigLoader,
) {
    private val log = LoggerFactory.getLogger(GiteaClient::class.java)

    private val json =
        ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun isEnabled(workingDir: Path = Paths.get(".")): Boolean = isEnabled(configLoader.load(workingDir))

    /** Publishes a commit status for [sha]; returns false when disabled or the request failed. */
    fun publishStatus(
        sha: String,
        status: BuildStatus,
        description: String,
        targetUrl: String? = null,
        workingDir: Path = Paths.get("."),
    ): Boolean {
        val config = configLoader.load(workingDir)
        if (!isEnabled(config)) {
            log.debug("Gitea status publishing is disabled; not publishing status for {}.", sha)
            return false
        }
        val body = mutableMapOf<String, String>()
        body["state"] = status.toGiteaState()
        body["context"] = config.gitea.statusContext
        body["description"] = description
        if (!targetUrl.isNullOrBlank()) {
            body["target_url"] = targetUrl
        }
        return try {
            restClient(config)
                .post()
                .uri("/api/v1/repos/{owner}/{repo}/statuses/{sha}", config.gitea.owner, config.gitea.repo, sha)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: RestClientException) {
            log.warn("Could not publish Gitea status for {}: {}", sha, e.message)
            false
        }
    }

    /** Reads the newest commit status for [sha] matching the configured `gitea.statusContext`. */
    fun readStatus(
        sha: String,
        workingDir: Path = Paths.get("."),
    ): GiteaStatusResult {
        val config = configLoader.load(workingDir)
        if (!isEnabled(config)) {
            return GiteaStatusResult.Disabled
        }
        val body =
            try {
                restClient(config)
                    .get()
                    .uri(
                        "/api/v1/repos/{owner}/{repo}/commits/{sha}/statuses?sort=recentupdate",
                        config.gitea.owner,
                        config.gitea.repo,
                        sha,
                    ).retrieve()
                    .body(String::class.java)
            } catch (e: RestClientException) {
                log.warn("Could not read Gitea status for {}: {}", sha, e.message)
                return GiteaStatusResult.Error("Gitea status request failed: ${e.message}")
            } ?: return GiteaStatusResult.Error("Gitea status request returned an empty response")
        val statuses =
            try {
                json.readValue(body, object : TypeReference<List<GiteaCommitStatus>>() {})
            } catch (e: JacksonException) {
                log.warn("Could not parse Gitea status response for {}: {}", sha, e.message)
                return GiteaStatusResult.Error("Gitea status response is not valid JSON: ${e.message}")
            }
        // sort=recentupdate returns newest first, so the first context match is the current status
        val newest =
            statuses.firstOrNull { it.context == config.gitea.statusContext }
                ?: return GiteaStatusResult.None
        val status =
            buildStatusFromGiteaState(newest.state.orEmpty())
                ?: return GiteaStatusResult.Error("Gitea reported unknown status state '${newest.state}'")
        return GiteaStatusResult.Found(status)
    }

    /**
     * Resolves the username owning `git.token` via the Gitea user API;
     * used as fallback for `git.account`. Returns null when unconfigured or on failure.
     * Unlike [isEnabled] this only needs `gitea.baseUrl` and `git.token`.
     */
    fun resolveUsername(workingDir: Path = Paths.get(".")): String? {
        val config = configLoader.load(workingDir)
        if (config.gitea.baseUrl.isBlank() || config.git.token.isBlank()) {
            return null
        }
        return try {
            restClient(config)
                .get()
                .uri("/api/v1/user")
                .retrieve()
                .body(String::class.java)
                ?.let {
                    json
                        .readTree(it)
                        .path("login")
                        .asText()
                        .ifEmpty { null }
                }
        } catch (e: RestClientException) {
            log.warn("Could not resolve Gitea username: {}", e.message)
            null
        } catch (e: JacksonException) {
            log.warn("Could not parse Gitea user response: {}", e.message)
            null
        }
    }

    private fun isEnabled(config: GitTallyConfig): Boolean =
        config.gitea.baseUrl.isNotBlank() &&
            config.gitea.owner.isNotBlank() &&
            config.gitea.repo.isNotBlank() &&
            config.git.token.isNotBlank()

    private fun restClient(config: GitTallyConfig): RestClient =
        RestClient
            .builder()
            .baseUrl(config.gitea.baseUrl.trimEnd('/'))
            .defaultHeader("Authorization", "token ${config.git.token}")
            .build()

    private data class GiteaCommitStatus(
        val context: String? = null,
        val state: String? = null,
    )
}
