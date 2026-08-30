package de.hoennig.werkator.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

@Service
class ConfigLoader(
    /** The running version, for the `werkator.version` check; absent outside a built jar (IDE, tests). */
    private val buildProperties: ObjectProvider<BuildProperties>? = null,
) {
    private val log = LoggerFactory.getLogger(ConfigLoader::class.java)

    private val yaml =
        ObjectMapper(YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

    /** Keys already reported by [dropNonDefinitionBuilds]; the config is loaded on every poll cycle. */
    private val warnedBuildKeys = ConcurrentHashMap.newKeySet<String>()

    /** Version warnings already reported; the config is loaded on every poll cycle, per branch. */
    private val warnedVersions = ConcurrentHashMap.newKeySet<String>()

    /** Section-level warnings already reported, keyed by a fixed slug; the config is loaded on every poll cycle. */
    private val warnedSections = ConcurrentHashMap.newKeySet<String>()

    fun load(workingDir: Path = Paths.get(".")): WerkatorConfig = toConfig(loadRaw(workingDir))

    /**
     * Config for building a branch in [worktreeDir]: the worktree's `.werkator.yml`
     * (the committed config of the branch being built) is applied as the branch layer,
     * see [loadWithBranchLayer]. With no worktree `.werkator.yml` this is identical
     * to [load].
     */
    fun loadForWorktree(
        workingDir: Path,
        worktreeDir: Path,
    ): WerkatorConfig = withBranchLayer(workingDir, loadFile(worktreeDir.resolve(ConfigFiles.firstExisting(worktreeDir)).toFile()))

    /**
     * The primary/`.git` config with the committed `.werkator.yml` of one branch
     * ([branchConfigYaml], null or blank for a branch without one) merged on top:
     * precedence branch > `.git` > project. A branch describes its own CI — build
     * settings (`buildCommand`, `cleanCommand`, `artifactDirs`, `docker.image`/`env`, …)
     * and its `builds` definitions — so a new configuration can be tried out on a
     * branch without touching any other branch's builds.
     *
     * The [pinned][stripPinned] keys are the exception, and they are exactly the ones
     * that are not a description of this branch's build: secrets (`git`), the host- and
     * repository-side sections (`server`, `gitea`, `executor`, `watcher`), the docker
     * sandbox policy (`docker.enabled`/`docker.network`), and the trust gate
     * (`requirePullRequest`, which decides whether the branch is built at all).
     * They are stripped from the branch layer before merging, so a branch can neither
     * escape its container, nor bypass its own pull-request gate, nor raise the global
     * concurrency, nor reach the credentials.
     */
    fun loadWithBranchLayer(
        workingDir: Path,
        branchConfigYaml: String?,
    ): WerkatorConfig = withBranchLayer(workingDir, parseYaml(branchConfigYaml))

    private fun withBranchLayer(
        workingDir: Path,
        branchLayer: Map<String, Any?>,
    ): WerkatorConfig {
        // scoped to this branch: an incompatible branch config fails its own builds and
        // must never stop the server or hold up the branches that are fine
        checkVersion(branchLayer, "the committed .werkator.yml of this branch", BRANCH_HINT)
        checkTriggerBlocks(branchLayer, "the committed .werkator.yml of this branch", BRANCH_HINT)
        return toConfig(deepMerge(loadRaw(workingDir), stripPinned(branchLayer)))
    }

    private fun toConfig(raw: Map<String, Any?>): WerkatorConfig {
        val config =
            if (raw.isEmpty()) {
                WerkatorConfig()
            } else {
                yaml.convertValue(resolveBuildSections(dropNonDefinitionBuilds(raw)), WerkatorConfig::class.java)
            }
        return defaultPublicBaseUrl(config)
    }

    /**
     * Ignores `builds` entries that are not a build definition — a scalar where a
     * definition belongs, most likely the `builds.maxConcurrent` key that moved to
     * `executor.maxConcurrent`. Such a leftover is a warning, not a startup failure:
     * the config lives in a repository whose `master` may not be changeable right now,
     * and the rest of it is perfectly usable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun dropNonDefinitionBuilds(raw: Map<String, Any?>): Map<String, Any?> {
        val builds = raw["builds"] as? Map<String, Any?> ?: return raw
        val definitions = builds.filterValues { it is Map<*, *> }
        if (definitions.size == builds.size) {
            return raw
        }
        for (key in builds.keys - definitions.keys) {
            if (!warnedBuildKeys.add(key)) {
                continue
            }
            if (key == "maxConcurrent") {
                log.warn("ignoring builds.maxConcurrent; the concurrency limit is executor.maxConcurrent since v0.9.15")
            } else {
                log.warn("ignoring builds.{}: a build definition must be a mapping of keys", key)
            }
        }
        return raw + ("builds" to definitions)
    }

    /**
     * Removes the keys a branch must never override: the secret and host-side top-level
     * sections, the trust gate, and the docker sandbox policy — the latter two wherever
     * they may appear, in a `builds` definition as well as in a legacy `branches` entry.
     * See [loadWithBranchLayer].
     *
     * There is one rule here, not two: a pinned key is dropped from the branch layer and
     * then resolves from whichever remaining layer sets it. The documentation still names
     * two groups — *host-pinned* for what only the machine can know (`git`, `server`) and
     * *master-pinned* for what belongs in the repository but must not be decided per
     * branch (`gitea`, `executor`, `watcher`, `requirePullRequest`, `statusContext`).
     * That distinction says where a key is meant to live, not how it is stripped, and it
     * is not visible here: `docker.enabled`/`network` moves from the first group to the
     * second as soon as the committed configuration carries them.
     */
    @Suppress("UNCHECKED_CAST")
    private fun stripPinned(branchLayer: Map<String, Any?>): Map<String, Any?> {
        if (branchLayer.isEmpty()) {
            return branchLayer
        }
        val result = branchLayer.toMutableMap()
        PINNED_TOP_LEVEL_KEYS.forEach { result.remove(it) }
        for (section in listOf("builds", "branches")) {
            val entries = result[section] as? Map<String, Any?> ?: continue
            result[section] = entries.mapValues { (_, value) -> stripPinnedSettings(value) }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun stripPinnedSettings(value: Any?): Any? {
        val entry = value as? Map<String, Any?> ?: return value
        val result = entry.toMutableMap()
        PINNED_SETTING_KEYS.forEach { result.remove(it) }
        val docker = entry["docker"] as? Map<String, Any?>
        if (docker != null) {
            val strippedDocker = docker.toMutableMap().apply { PINNED_DOCKER_KEYS.forEach { remove(it) } }
            if (strippedDocker.isEmpty()) result.remove("docker") else result["docker"] = strippedDocker
        }
        return result
    }

    /**
     * Decides which of the two sections describes the builds: `builds` or the legacy
     * `branches`, never both. As soon as the merged configuration carries one real build
     * definition — `builds.maxConcurrent` alone is not one, it is already dropped by
     * [dropNonDefinitionBuilds] — a `branches` section is ignored altogether, because a
     * definition now carries the complete description of its build and two half-answers
     * would silently pull against each other.
     *
     * Deliberately decided on the *merged* map, after all layers are in: a branch that
     * brings its own `builds` therefore also switches the host's `branches` off for its
     * own builds, and — the reason this order matters — a build the branch defines and
     * the host has never heard of still inherits the host's `builds.default`, sandbox
     * policy included. Were the sections resolved per layer, that build would start with
     * an empty docker policy and run natively on the host, which is exactly the escape
     * the pinned keys exist to prevent.
     */
    private fun resolveBuildSections(raw: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val definitions = raw["builds"] as? Map<String, Any?> ?: emptyMap()
        if (definitions.isEmpty()) {
            return mergeBranchDefaults(raw)
        }
        if (raw.containsKey("branches") && warnedSections.add(LEGACY_BRANCHES_WARNING)) {
            log.warn(
                "ignoring the branches section: this configuration defines builds, and a build definition " +
                    "carries its own settings; move what is still needed into builds — branches is going away",
            )
        }
        warnWhenNothingIsTriggered(definitions)
        return mergeBuildDefaults(raw - "branches")
    }

    /**
     * An explicit `builds.default` replaces the implicit on-push build, so a set of
     * definitions can end up with no trigger at all — an instance that will never build
     * anything. That is a plausible intention for a moment and a mistake for a week, so
     * it is said out loud once instead of being enforced.
     */
    private fun warnWhenNothingIsTriggered(definitions: Map<String, Any?>) {
        if (BuildDefinition.DEFAULT !in definitions || definitions.values.any { isTriggered(it) }) {
            return
        }
        if (warnedSections.add(NO_TRIGGER_WARNING)) {
            log.warn("no build defines onPush or atTimes; the watcher will never start a build on its own")
        }
    }

    private fun isTriggered(definition: Any?): Boolean {
        val trigger = (definition as? Map<*, *>)?.get("trigger") as? Map<*, *> ?: return false
        return trigger["onPush"] == true || (trigger["atTimes"] as? List<*>)?.isNotEmpty() == true
    }

    /**
     * Refuses a definition that still writes its trigger and selector keys flat instead of
     * inside `trigger`. Silently ignoring them would leave a build with no trigger at all —
     * a branch that stops building without saying so, which is worse than not starting.
     * Scoped like [checkVersion]: per file, so the message names the one to fix.
     */
    private fun checkTriggerBlocks(
        raw: Map<String, Any?>,
        source: String,
        hint: String,
    ) {
        val builds = raw["builds"] as? Map<*, *> ?: return
        val offenders =
            builds.entries.mapNotNull { (name, value) ->
                val flat = (value as? Map<*, *>)?.keys?.filter { it in FLAT_TRIGGER_KEYS } ?: return@mapNotNull null
                flat.takeIf { it.isNotEmpty() }?.let { "builds.$name: ${it.joinToString(", ")}" }
            }
        if (offenders.isEmpty()) {
            return
        }
        throw ConfigFormatException(
            "$source declares a build trigger outside its trigger block (${offenders.joinToString("; ")}). " +
                "Move these keys into a `trigger:` block inside the definition. $hint",
        )
    }

    /**
     * Applies `builds.default` as the base of every other build definition — the settings
     * only. A trigger is never inherited: `onPush` and `atTimes` say when *this* build
     * runs, and the selectors say for which branches, so inheriting them would make every
     * job fire whenever the default one does.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mergeBuildDefaults(raw: Map<String, Any?>): Map<String, Any?> {
        val builds = raw["builds"] as? Map<String, Any?> ?: return raw
        val base = (builds[BuildDefinition.DEFAULT] as? Map<String, Any?>)?.minus(TRIGGER_KEYS) ?: return raw
        if (base.isEmpty()) {
            return raw
        }
        val merged =
            builds.mapValues { (name, value) ->
                if (name == BuildDefinition.DEFAULT) {
                    value
                } else {
                    deepMerge(base, value as? Map<String, Any?> ?: emptyMap())
                }
            }
        return raw + ("builds" to merged)
    }

    /** Legacy default: an empty `server.publicBaseUrl` becomes `https://<nginx.serverName>/`. */
    private fun defaultPublicBaseUrl(config: WerkatorConfig): WerkatorConfig {
        if (config.server.publicBaseUrl.isNotBlank() ||
            config.server.nginx.serverName
                .isBlank()
        ) {
            return config
        }
        return config.copy(server = config.server.copy(publicBaseUrl = "https://${config.server.nginx.serverName}/"))
    }

    fun loadRaw(workingDir: Path = Paths.get(".")): Map<String, Any?> {
        // each layer under its current name, or under the one it had before the rename
        val repoInstallName = ConfigFiles.firstExisting(workingDir, ConfigFiles.repoInstall)
        val projectName = ConfigFiles.firstExisting(workingDir)
        val repoInstall = loadFile(workingDir.resolve(repoInstallName).toFile())
        val project = loadFile(workingDir.resolve(projectName).toFile())
        // per file, so the message names the file to fix — the merged map has no provenance
        checkVersion(project, projectName, ROLLBACK_HINT)
        checkVersion(repoInstall, repoInstallName, ROLLBACK_HINT)
        checkTriggerBlocks(project, projectName, ROLLBACK_HINT)
        checkTriggerBlocks(repoInstall, repoInstallName, ROLLBACK_HINT)
        return deepMerge(project, repoInstall)
    }

    /**
     * Enforces the `werkator.version` declaration of one configuration file.
     * An incompatible file throws — reading it would mean honoring keys that mean
     * something else now, which is worse than not building. A file that merely exceeds
     * its own `below` marker is a warning, logged once: an unmaintained marker must
     * never stop a CI.
     */
    private fun checkVersion(
        raw: Map<String, Any?>,
        source: String,
        hint: String,
    ) {
        if (raw.isEmpty()) {
            return
        }
        val running = buildProperties?.getIfAvailable()?.version
        when (val verdict = ConfigVersions.verdict(requirementOf(raw), running)) {
            is VersionVerdict.Compatible -> Unit
            is VersionVerdict.Warn ->
                if (warnedVersions.add("$source: ${verdict.message}")) {
                    log.warn("{} {}", source, verdict.message)
                }
            is VersionVerdict.Incompatible -> throw ConfigVersionException("$source ${verdict.message}. $hint")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun requirementOf(raw: Map<String, Any?>): VersionRequirement {
        val version = (raw["werkator"] as? Map<String, Any?>)?.get("version") as? Map<String, Any?> ?: return VersionRequirement()
        return VersionRequirement(
            since = version["since"]?.toString()?.trim().orEmpty(),
            below = version["below"]?.toString()?.trim().orEmpty(),
        )
    }

    fun toYaml(value: Any): String = yaml.writeValueAsString(value)

    private fun loadFile(file: File): Map<String, Any?> {
        if (!file.exists()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return yaml.readValue(file, Map::class.java) as Map<String, Any?>
    }

    /** Parses a `.werkator.yml` read from git (not from disk); blank or null yields no layer. */
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
         * builds, which is the branch's own business — only the individual settings in
         * [PINNED_SETTING_KEYS] and [PINNED_DOCKER_KEYS] are taken out of it.
         */
        private val PINNED_TOP_LEVEL_KEYS = setOf("git", "gitea", "server", "executor", "watcher")

        /**
         * Settings keys a branch must never override, in a build definition as well as in
         * a legacy branch entry: the trust gate that decides whether the watcher builds
         * this branch at all, and the Gitea check this build reports as — a branch that
         * could choose its own context could take over the check a branch protection
         * rule depends on.
         */
        private val PINNED_SETTING_KEYS = setOf("requirePullRequest", "statusContext")

        /** `docker` keys a branch must never override: the sandbox policy. */
        private val PINNED_DOCKER_KEYS = setOf("enabled", "network")

        /**
         * The one key of a build definition that says *when* and *for which branches* it
         * runs; never inherited from `builds.default`. A single key on purpose: a selector
         * added inside it is non-inheritable by construction, where a list of key names
         * would have to be remembered.
         */
        private val TRIGGER_KEYS = setOf("trigger")

        /** The keys that moved into [TRIGGER_KEYS]; still writing them flat is refused, not ignored. */
        private val FLAT_TRIGGER_KEYS = setOf("onPush", "atTimes", "branches", "activeWithin")

        private const val LEGACY_BRANCHES_WARNING = "legacy-branches-ignored"

        private const val NO_TRIGGER_WARNING = "no-build-triggered"

        private const val ROLLBACK_HINT =
            "Migrate the file, or roll back to the Werkator version it was written for."

        private const val BRANCH_HINT =
            "Migrate the file on this branch; the other branches keep building."
    }
}
