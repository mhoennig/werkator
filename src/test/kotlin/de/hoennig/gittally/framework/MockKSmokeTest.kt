package de.hoennig.gittally.framework

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

interface Greeter {
    fun greet(name: String): String
}

class MockKSmokeTest :
    FunSpec({

        test("mockk stubs and verifies") {
            val greeter = mockk<Greeter>()
            every { greeter.greet("world") } returns "hello, world"

            greeter.greet("world") shouldBe "hello, world"

            verify(exactly = 1) { greeter.greet("world") }
        }
    })
