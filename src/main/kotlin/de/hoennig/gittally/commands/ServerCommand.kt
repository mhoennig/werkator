package de.hoennig.werkator.commands

import de.hoennig.werkator.WerkatorApplication
import de.hoennig.werkator.config.ConfigLoader
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch

/**
 * The CLI context runs without a web server (web type `none` in `application.yml`),
 * so this command launches a second `SpringApplication` with
 * `WebApplicationType.SERVLET` and the `server` profile, then blocks until shutdown.
 * The profile switches the web type via `application-server.yml` (`spring.main.*`
 * beats programmatic builder settings), starts the watcher via `ServerWatcherLifecycle`,
 * and keeps `CliRunner` out of the second context.
 */
@Component
@Command(
    name = "server",
    description = ["Start the werkator server"],
    mixinStandardHelpOptions = true,
)
class ServerCommand(
    private val configLoader: ConfigLoader,
) : Runnable {
    var workingDir: Path = Paths.get(".")

    override fun run() {
        val config = configLoader.load(workingDir)
        val context =
            SpringApplicationBuilder(WerkatorApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .profiles(SERVER_PROFILE)
                .properties(
                    "server.port=${config.server.port}",
                    "server.address=${config.server.bindAddress}",
                ).run()
        val port = context.environment.getProperty("local.server.port", config.server.port.toString())
        println("werkator server listening on http://${config.server.bindAddress}:$port/ — Ctrl-C to stop")
        awaitShutdown(context)
    }

    /** Blocks until the context closes; Ctrl-C triggers the shutdown hook Spring registered. */
    private fun awaitShutdown(context: ConfigurableApplicationContext) {
        val closed = CountDownLatch(1)
        context.addApplicationListener(ApplicationListener<ContextClosedEvent> { closed.countDown() })
        if (context.isActive) {
            closed.await()
        }
        if (jvmIsShuttingDown()) {
            // Parked so that main() never races the shutdown hooks — `SpringApplication.exit`
            // on the closing CLI context would print a stack trace. The JVM halts once the
            // hooks have finished; only a programmatic context close falls through.
            Thread.currentThread().join()
        }
    }

    /** Once the JVM shuts down, registering a shutdown hook throws. */
    private fun jvmIsShuttingDown(): Boolean =
        try {
            val probe = Thread { }
            Runtime.getRuntime().addShutdownHook(probe)
            Runtime.getRuntime().removeShutdownHook(probe)
            false
        } catch (_: IllegalStateException) {
            true
        }

    companion object {
        const val SERVER_PROFILE = "server"
    }
}
