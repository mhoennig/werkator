package de.hoennig.gittally

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import picocli.CommandLine
import picocli.CommandLine.IFactory
import kotlin.system.exitProcess

@SpringBootApplication
class GitTallyApplication(
    private val factory: IFactory,
    private val rootCommand: GitTallyCommand,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        exitProcess(CommandLine(rootCommand, factory).execute(*args))
    }
}

fun main(args: Array<String>) {
    runApplication<GitTallyApplication>(*args)
}
