package de.hoennig.gittally

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.IFactory
import kotlin.system.exitProcess

@SpringBootApplication
class GitTallyApplication

@Component
class CliRunner(
    private val factory: IFactory,
    private val rootCommand: GitTallyCommand,
) : CommandLineRunner, ExitCodeGenerator {
    private var exitCode = 0

    override fun run(vararg args: String) {
        exitCode = CommandLine(rootCommand, factory).execute(*args)
    }

    override fun getExitCode() = exitCode
}

fun main(args: Array<String>) {
    exitProcess(SpringApplication.exit(runApplication<GitTallyApplication>(*args)))
}
