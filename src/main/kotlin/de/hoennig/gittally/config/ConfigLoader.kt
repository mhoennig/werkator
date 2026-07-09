package de.hoennig.gittally.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

@Service
class ConfigLoader {
    private val yaml =
        ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

    fun load(workingDir: Path = Paths.get(".")): GitTallyConfig = toConfig(loadRaw(workingDir))

    /**
     * Config for building a branch in [worktreeDir]: the worktree's `.gittally.yml`
     * (the committed config of the branch being built) overrides the primary/`.git`
     * config, giving the precedence worktree > `.git` > project. So a branch controls
     * its own build settings (`buildCommand`, `cleanCommand`, `artifactDirs`,
     * `docker.image`/`env`, …).
     *
     * The [pinned][stripPinned] keys are the exception: secrets (`git`), `gitea`/`server`
     * settings, and the docker sandbox policy (`docker.enabled`/`docker.network`) always
     * come from `.git`/primary — a branch must never be able to disable its own container,
     * change its network mode, or reach the credentials. They are stripped from the
     * worktree layer before it is merged, so a worktree cannot set them at all.
     *
     * With no worktree `.gittally.yml` this is identical to [load].
     */
    fun loadForWorktree(
        workingDir: Path,
        worktreeDir: Path,
    ): GitTallyConfig {
        val primary = loadRaw(workingDir)
        val worktree = stripPinned(loadFile(worktreeDir.resolve(".gittally.yml").toFile()))
        return toConfig(deepMerge(primary, worktree))
    }

    private fun toConfig(raw: Map<String, Any?>): GitTallyConfig {
        val config =
            if (raw.isEmpty()) {
                GitTallyConfig()
            } else {
                yaml.convertValue(mergeBranchDefaults(raw), GitTallyConfig::class.java)
            }
        return defaultPublicBaseUrl(config)
    }

    /**
     * Removes the keys a build worktree must never override: the secret/server-side
     * top-level sections and the per-branch docker sandbox policy. See [loadForWorktree].
     */
    @Suppress("UNCHECKED_CAST")
    private fun stripPinned(worktree: Map<String, Any?>): Map<String, Any?> {
        if (worktree.isEmpty()) {
            return worktree
        }
        val result = worktree.toMutableMap()
        PINNED_TOP_LEVEL_KEYS.forEach { result.remove(it) }
        val branches = result["branches"] as? Map<String, Any?>
        if (branches != null) {
            result["branches"] =
                branches.mapValues { (_, value) ->
                    val branch = value as? Map<String, Any?> ?: return@mapValues value
                    val docker = branch["docker"] as? Map<String, Any?> ?: return@mapValues branch
                    val strippedDocker = docker.toMutableMap().apply { PINNED_DOCKER_KEYS.forEach { remove(it) } }
                    branch.toMutableMap().apply {
                        if (strippedDocker.isEmpty()) remove("docker") else put("docker", strippedDocker)
                    }
                }
        }
        return result
    }

    /** Legacy default: an empty `server.publicBaseUrl` becomes `https://<nginx.serverName>/`. */
    private fun defaultPublicBaseUrl(config: GitTallyConfig): GitTallyConfig {
        if (config.server.publicBaseUrl.isNotBlank() ||
            config.server.nginx.serverName
                .isBlank()
        ) {
            return config
        }
        return config.copy(server = config.server.copy(publicBaseUrl = "https://${config.server.nginx.serverName}/"))
    }

    fun loadRaw(workingDir: Path = Paths.get(".")): Map<String, Any?> {
        val repoInstall = loadFile(workingDir.resolve(".git/gittally/.gittally.yml").toFile())
        val project = loadFile(workingDir.resolve(".gittally.yml").toFile())
        return deepMerge(project, repoInstall)
    }

    fun toYaml(value: Any): String = yaml.writeValueAsString(value)

    private fun loadFile(file: File): Map<String, Any?> {
        if (!file.exists()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return yaml.readValue(file, Map::class.java) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeBranchDefaults(raw: Map<String, Any?>): Map<String, Any?> {
        val branches = raw["branches"] as? Map<String, Any?> ?: return raw
        val default = branches["default"] as? Map<String, Any?> ?: return raw
        if (default.isEmpty()) return raw
        val merged =
            branches.mapValues { (name, value) ->
                if (name == "default") {
                    value
                } else {
                    deepMerge(default, value as? Map<String, Any?> ?: emptyMap())
                }
            }
        return raw + ("branches" to merged)
    }

    @Suppress("UNCHECKED_CAST")
    private fun deepMerge(
        base: Map<String, Any?>,
        overlay: Map<String, Any?>,
    ): Map<String, Any?> {
        val result = base.toMutableMap()
        for ((key, value) in overlay) {
            val existing = result[key]
            result[key] =
                if (existing is Map<*, *> && value is Map<*, *>) {
                    deepMerge(existing as Map<String, Any?>, value as Map<String, Any?>)
                } else {
                    value
                }
        }
        return result
    }

    companion object {
        /** Top-level sections a build worktree must never override: secrets and server-side settings. */
        private val PINNED_TOP_LEVEL_KEYS = setOf("git", "gitea", "server")

        /** Per-branch `docker` keys the worktree must never override: the sandbox policy. */
        private val PINNED_DOCKER_KEYS = setOf("enabled", "network")
    }
}
