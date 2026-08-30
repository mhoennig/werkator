package de.hoennig.werkator

import de.hoennig.werkator.commands.ConfigPrintCommand
import de.hoennig.werkator.commands.InitCommand
import de.hoennig.werkator.commands.ServerCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ApplicationContextTest : FunSpec() {
    @Autowired
    lateinit var rootCommand: werkatorCommand

    @Autowired
    lateinit var initCommand: InitCommand

    @Autowired
    lateinit var serverCommand: ServerCommand

    @Autowired
    lateinit var configPrintCommand: ConfigPrintCommand

    init {
        test("application context loads") {
            rootCommand shouldNotBe null
        }

        test("all subcommands are wired") {
            initCommand shouldNotBe null
            serverCommand shouldNotBe null
            configPrintCommand shouldNotBe null
        }
    }
}
