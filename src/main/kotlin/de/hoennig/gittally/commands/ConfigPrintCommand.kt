package de.hoennig.gittally.commands

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Component
@Command(
    name = "config:print",
    description = ["Print the effective configuration"],
    mixinStandardHelpOptions = true,
)
class ConfigPrintCommand : Runnable {
    @Option(names = ["--full"], description = ["Include all defaults"])
    var full: Boolean = false

    override fun run() {
        println("config:print${if (full) " --full" else ""} – not yet implemented")
    }
}
