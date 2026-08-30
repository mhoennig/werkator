package de.hoennig.werkator.commands

import de.hoennig.werkator.config.ConfigLoader
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Component
@Command(
    name = "config:print",
    description = ["Print the effective configuration"],
    mixinStandardHelpOptions = true,
)
class ConfigPrintCommand(
    private val configLoader: ConfigLoader,
) : Runnable {
    @Option(names = ["--full"], description = ["Include all defaults"])
    var full: Boolean = false

    @Option(names = ["--show-secrets"], description = ["Print secrets (git.token) in clear text instead of masked"])
    var showSecrets: Boolean = false

    override fun run() {
        if (full) {
            val config = configLoader.load()
            printMaskingNote(config.git.token)
            print(configLoader.toYaml(if (showSecrets) config else config.copy(git = config.git.copy(token = MASK))))
        } else {
            val raw = configLoader.loadRaw()
            if (raw.isEmpty()) {
                println("(no configuration files found)")
            } else {
                printMaskingNote(rawToken(raw))
                print(configLoader.toYaml(if (showSecrets) raw else maskToken(raw)))
            }
        }
    }

    /** A YAML comment, so the output stays parseable when piped into a file. */
    private fun printMaskingNote(token: String?) {
        if (!showSecrets && !token.isNullOrEmpty()) {
            println("# git.token is masked — pass --show-secrets to print it")
        }
    }

    private fun rawToken(raw: Map<String, Any?>): String? = (raw["git"] as? Map<*, *>)?.get("token") as? String

    private fun maskToken(raw: Map<String, Any?>): Map<String, Any?> {
        val git = raw["git"] as? Map<*, *> ?: return raw
        if (rawToken(raw).isNullOrEmpty()) return raw
        return raw + ("git" to git.entries.associate { (key, value) -> key to if (key == "token") MASK else value })
    }

    companion object {
        private const val MASK = "***"
    }
}
