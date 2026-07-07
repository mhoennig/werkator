package de.hoennig.gittally.commands

import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.server.UiFormats
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.ExitCode
import picocli.CommandLine.Option
import java.util.concurrent.Callable

/**
 * Reads the result repository directly, so it also works (read-only) while a server
 * instance is running against the same repository.
 */
@Component
@Command(
    name = "status",
    description = ["Print the latest build per branch"],
    mixinStandardHelpOptions = true,
)
class StatusCommand(
    private val repository: BuildResultRepository,
) : Callable<Int> {
    @Option(names = ["--history"], description = ["Print all recorded builds, not only the latest per branch"])
    var history: Boolean = false

    override fun call(): Int {
        val results = if (history) repository.history() else repository.latestPerBranch()
        if (results.isEmpty()) {
            println("(no builds recorded)")
        } else {
            printTable(results)
        }
        return ExitCode.OK
    }

    private fun printTable(results: List<BuildResult>) {
        val header = listOf("BRANCH", "STATUS", "COMMIT", "TIME", "DURATION")
        val rows =
            results.map {
                listOf(
                    it.branch,
                    it.status.name.lowercase(),
                    it.commit.take(12),
                    UiFormats.timestamp(it.startedAt),
                    UiFormats.duration(it.duration),
                )
            }
        val widths = header.indices.map { column -> (rows + listOf(header)).maxOf { row -> row[column].length } }
        for (row in listOf(header) + rows) {
            println(row.mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString("  ").trimEnd())
        }
    }
}
