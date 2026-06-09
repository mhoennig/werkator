package de.hoennig.gittally.commands

import org.springframework.stereotype.Component
import picocli.CommandLine.Command

@Component
@Command(
    name = "init",
    description = ["Initialize GitTally for the current repository"],
    mixinStandardHelpOptions = true,
)
class InitCommand : Runnable {
    override fun run() {
        println("init – not yet implemented")
    }
}
