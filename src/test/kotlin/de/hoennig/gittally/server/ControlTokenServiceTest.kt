package de.hoennig.gittally.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.nio.file.Path

class ControlTokenServiceTest : FunSpec() {
    private fun newTokenFile(): Path = Files.createTempDirectory("gittally-token-test").resolve("control-token")

    init {
        test("generates a hex token once and persists it") {
            val tokenFile = newTokenFile()
            val service = ControlTokenService(tokenFile)

            val token = service.token()

            token shouldMatch Regex("[0-9a-f]{48}")
            Files.readString(tokenFile).trim() shouldBe token
            service.token() shouldBe token
        }

        test("reuses an operator-provided token file") {
            val tokenFile = newTokenFile()
            Files.createDirectories(tokenFile.parent)
            Files.writeString(tokenFile, "my-own-token\n")

            ControlTokenService(tokenFile).token() shouldBe "my-own-token"
        }

        test("matches only the exact token") {
            val service = ControlTokenService(newTokenFile())
            val token = service.token()

            service.matches(token) shouldBe true
            service.matches(token + "x") shouldBe false
            service.matches("") shouldBe false
            service.matches(null) shouldBe false
        }
    }
}
