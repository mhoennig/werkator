package de.hoennig.gittally

import de.hoennig.gittally.commands.BuildCommand
import de.hoennig.gittally.commands.ConfigPrintCommand
import de.hoennig.gittally.commands.InitCommand
import de.hoennig.gittally.commands.RetryCommand
import de.hoennig.gittally.commands.ServerCommand
import de.hoennig.gittally.commands.StatusCommand
import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.Command

@Component
@Command(
    name = "gittally",
    subcommands = [
        InitCommand::class,
        ServerCommand::class,
        StatusCommand::class,
        BuildCommand::class,
        RetryCommand::class,
        ConfigPrintCommand::class,
    ],
    mixinStandardHelpOptions = true,
    description = ["Lightweight, declarative CI/CD system"],
)
class GitTallyCommand : Runnable {
    override fun run(): Unit = throw CommandLine.ParameterException(CommandLine(this), "Specify a subcommand")
}
