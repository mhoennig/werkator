package de.hoennig.gittally.framework

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

interface MessageService {
    fun getMessage(): String
}

/** Workaround for springmockk incompatibility: register MockK mock via @TestConfiguration. */
@SpringBootTest
@Import(SpringMockKSmokeTest.Mocks::class)
class SpringMockKSmokeTest : FunSpec() {
    @TestConfiguration
    class Mocks {
        @Bean
        fun messageService(): MessageService = mockk()
    }

    @Autowired
    lateinit var messageService: MessageService

    init {
        beforeEach { clearMocks(messageService) }

        test("MockK mock registered as Spring bean can be stubbed and verified") {
            every { messageService.getMessage() } returns "hello from mock"

            messageService.getMessage() shouldBe "hello from mock"

            verify(exactly = 1) { messageService.getMessage() }
        }
    }
}

/** Verifies whether @MockkBean works with the current Spring Boot version (springmockk 4.0.2). */
@SpringBootTest
class MockkBeanSmokeTest : FunSpec() {
    @MockkBean
    lateinit var messageService: MessageService

    init {
        beforeEach { clearMocks(messageService) }

        test("@MockkBean stubs and verifies a Spring-managed MockK mock") {
            every { messageService.getMessage() } returns "hello from @MockkBean"

            messageService.getMessage() shouldBe "hello from @MockkBean"

            verify(exactly = 1) { messageService.getMessage() }
        }
    }
}
