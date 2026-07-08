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

    fun load(workingDir: Path = Paths.get(".")): GitTallyConfig {
        val raw = loadRaw(workingDir)
        val config =
            if (raw.isEmpty()) {
                GitTallyConfig()
            } else {
                yaml.convertValue(mergeBranchDefaults(raw), GitTallyConfig::class.java)
            }
        return defaultPublicBaseUrl(config)
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
}
