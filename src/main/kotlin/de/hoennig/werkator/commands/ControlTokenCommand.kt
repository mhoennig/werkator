package de.hoennig.werkator.commands

import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.server.ControlTokenService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * Prints the control token guarding the mutating build endpoints, creating it
 * exactly like the server does ([ControlTokenService] owns the format) — so no
 * wrapper script ever needs its own token generator (step 23).
 */
@Component
@Command(
    name = "control-token",
    description = ["Print the control token for the mutating build endpoints, creating it if missing"],
    mixinStandardHelpOptions = true,
)
class ControlTokenCommand(
    private val gitService: GitService,
) : Callable<Int> {
    var workingDir: Path = Paths.get(".")

    override fun call(): Int {
        val root =
            try {
                gitService.getTopLevel(workingDir.toAbsolutePath().normalize())
            } catch (e: Exception) {
                println("Error: ${e.message}")
                return 2
            }
        println(ControlTokenService(root.resolve(".git/werkator/control-token")).token())
        return 0
    }
}
