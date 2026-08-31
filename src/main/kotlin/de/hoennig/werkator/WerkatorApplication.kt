package de.hoennig.werkator

import de.hoennig.werkator.config.ConfigException
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.IFactory
import java.nio.file.Paths
import kotlin.system.exitProcess

@SpringBootApplication
class WerkatorApplication

/** Not in the `server` profile: the second context started by `ServerCommand` must not run picocli again. */
@Component
@Profile("!server")
class CliRunner(
    private val factory: IFactory,
    private val rootCommand: WerkatorCommand,
) : CommandLineRunner,
    ExitCodeGenerator {
    private var exitCode = 0

    override fun run(vararg args: String) {
        // before any command resolves a path under it, and once per process
        StateDirMigration.migrateIfNeeded(Paths.get("."))
        exitCode =
            CommandLine(rootCommand, factory)
                .setExecutionExceptionHandler { exception, commandLine, _ ->
                    // a config Werkator must not read is a stated fact, not a crash: the message
                    // names the file, the versions, and the way out — a stack trace would bury it
                    if (exception is ConfigException) {
                        commandLine.err.println("Error: ${exception.message}")
                        CONFIG_ERROR_EXIT_CODE
                    } else {
                        throw exception
                    }
                }.execute(*args)
    }

    override fun getExitCode() = exitCode

    companion object {
        /** Same code the commands use for usage and configuration errors. */
        const val CONFIG_ERROR_EXIT_CODE = 2
    }
}

fun main(args: Array<String>) {
    exitProcess(SpringApplication.exit(runApplication<WerkatorApplication>(*args)))
}
