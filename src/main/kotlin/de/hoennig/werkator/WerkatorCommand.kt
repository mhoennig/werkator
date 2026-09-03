package de.hoennig.werkator

import de.hoennig.werkator.commands.BuildCommand
import de.hoennig.werkator.commands.ConfigPrintCommand
import de.hoennig.werkator.commands.ControlTokenCommand
import de.hoennig.werkator.commands.InitCommand
import de.hoennig.werkator.commands.RetryCommand
import de.hoennig.werkator.commands.ServerCommand
import de.hoennig.werkator.commands.StatusCommand
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.Command

@Component
@Command(
    name = "werkator",
    subcommands = [
        InitCommand::class,
        ServerCommand::class,
        StatusCommand::class,
        BuildCommand::class,
        RetryCommand::class,
        ConfigPrintCommand::class,
        ControlTokenCommand::class,
    ],
    mixinStandardHelpOptions = true,
    versionProvider = BuildPropertiesVersionProvider::class,
    description = ["Lightweight, declarative CI/CD system"],
)
class WerkatorCommand : Runnable {
    override fun run(): Unit = throw CommandLine.ParameterException(CommandLine(this), "Specify a subcommand")
}

/**
 * Feeds `--version` from the Gradle project version via the `build-info`
 * [BuildProperties] — the same source as the web UI footer, so the version is
 * declared only in `build.gradle.kts`. Resolved through picocli's Spring factory;
 * `dev` when running from classes (IDE, tests) where no build info exists.
 */
@Component
class BuildPropertiesVersionProvider(
    private val buildProperties: ObjectProvider<BuildProperties>,
) : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("Werkator v${buildProperties.getIfAvailable()?.version ?: "dev"}")
}
