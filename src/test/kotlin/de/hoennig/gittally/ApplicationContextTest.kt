package de.hoennig.gittally

import de.hoennig.gittally.commands.ConfigPrintCommand
import de.hoennig.gittally.commands.InitCommand
import de.hoennig.gittally.commands.ServerCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ApplicationContextTest : FunSpec() {
    @Autowired
    lateinit var rootCommand: GitTallyCommand

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
