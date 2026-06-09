package de.hoennig.gittally.commands

import org.springframework.stereotype.Component
import picocli.CommandLine.Command

@Component
@Command(
    name = "server",
    description = ["Start the GitTally server"],
    mixinStandardHelpOptions = true,
)
class ServerCommand : Runnable {
    override fun run() {
        println("server – not yet implemented")
    }
}
