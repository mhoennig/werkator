package de.hoennig.gittally.commands

import de.hoennig.gittally.config.ConfigLoader
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

    override fun run() {
        if (full) {
            print(configLoader.toYaml(configLoader.load()))
        } else {
            val raw = configLoader.loadRaw()
            if (raw.isEmpty()) {
                println("(no configuration files found)")
            } else {
                print(configLoader.toYaml(raw))
            }
        }
    }
}
