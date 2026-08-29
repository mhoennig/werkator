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
     * (the committed config of the branch being built) is applied as the branch layer,
     * see [loadWithBranchLayer]. With no worktree `.gittally.yml` this is identical
     * to [load].
     */
    fun loadForWorktree(
        workingDir: Path,
        worktreeDir: Path,
    ): GitTallyConfig = toConfig(deepMerge(loadRaw(workingDir), stripPinned(loadFile(worktreeDir.resolve(".gittally.yml").toFile()))))

    /**
     * The primary/`.git` config with the committed `.gittally.yml` of one branch
     * ([branchConfigYaml], null or blank for a branch without one) merged on top:
     * precedence branch > `.git` > project. A branch describes its own CI — build
     * settings (`buildCommand`, `cleanCommand`, `artifactDirs`, `docker.image`/`env`, …)
     * and its `builds` definitions — so a new configuration can be tried out on a
     * branch without touching any other branch's builds.
     *
     * The [pinned][stripPinned] keys are the exception, and they are exactly the ones
     * that are not a description of this branch's build: secrets (`git`), the host- and
     * repository-side sections (`server`, `gitea`, `executor`), the docker sandbox policy
     * (`docker.enabled`/`docker.network`), and the trust gate
     * (`requirePullRequest`, which decides whether the branch is built at all).
     * They are stripped from the branch layer before merging, so a branch can neither
     * escape its container, nor bypass its own pull-request gate, nor raise the global
     * concurrency, nor reach the credentials.
     */
    fun loadWithBranchLayer(
        workingDir: Path,
        branchConfigYaml: String?,
    ): GitTallyConfig = toConfig(deepMerge(loadRaw(workingDir), stripPinned(parseYaml(branchConfigYaml))))

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
     * Removes the keys a branch must never override: the secret and host-side top-level
     * sections, the per-branch trust gate, and the docker sandbox policy.
     * See [loadWithBranchLayer].
     */
    @Suppress("UNCHECKED_CAST")
    private fun stripPinned(branchLayer: Map<String, Any?>): Map<String, Any?> {
        if (branchLayer.isEmpty()) {
            return branchLayer
        }
        val result = branchLayer.toMutableMap()
        PINNED_TOP_LEVEL_KEYS.forEach { result.remove(it) }
        val branches = result["branches"] as? Map<String, Any?>
        if (branches != null) {
            result["branches"] = branches.mapValues { (_, value) -> stripPinnedBranchKeys(value) }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun stripPinnedBranchKeys(value: Any?): Any? {
        val branch = value as? Map<String, Any?> ?: return value
        val result = branch.toMutableMap()
        PINNED_BRANCH_KEYS.forEach { result.remove(it) }
        val docker = branch["docker"] as? Map<String, Any?>
        if (docker != null) {
            val strippedDocker = docker.toMutableMap().apply { PINNED_DOCKER_KEYS.forEach { remove(it) } }
            if (strippedDocker.isEmpty()) result.remove("docker") else result["docker"] = strippedDocker
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

    /** Parses a `.gittally.yml` read from git (not from disk); blank or null yields no layer. */
    private fun parseYaml(text: String?): Map<String, Any?> {
        if (text.isNullOrBlank()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return yaml.readValue(text, Map::class.java) as? Map<String, Any?> ?: emptyMap()
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
        /**
         * Top-level sections a branch must never override, because none of them describes
         * this branch's build: secrets (`git`), and the host- and repository-side settings
         * (`server`, `gitea`, `executor`, `watcher`) — a branch must not be able to reach
         * the credentials, report statuses to another repository, raise the global
         * concurrency, or turn off the pull-request gate for the whole watcher.
         * The `builds` section is deliberately *not* pinned: it describes what the branch
         * builds, and a branch can already run any command via `branches.*.buildCommand`.
         */
        private val PINNED_TOP_LEVEL_KEYS = setOf("git", "gitea", "server", "executor", "watcher")

        /**
         * Per-branch keys a branch must never override: the trust gate that decides
         * whether the watcher builds this branch at all.
         */
        private val PINNED_BRANCH_KEYS = setOf("requirePullRequest")

        /** Per-branch `docker` keys a branch must never override: the sandbox policy. */
        private val PINNED_DOCKER_KEYS = setOf("enabled", "network")
    }
}
