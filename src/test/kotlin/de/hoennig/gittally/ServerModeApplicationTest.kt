package de.hoennig.werkator

import com.ninjasquad.springmockk.MockkBean
import de.hoennig.werkator.metrics.SystemMetricsCollector
import de.hoennig.werkator.watcher.Watcher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.verify
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient

/**
 * Proves the `server` profile boots a real web server and starts the watcher and
 * the metrics collector. Both are mocked so the test never fetches origin,
 * enqueues builds, or walks the repository for its size.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
class ServerModeApplicationTest : FunSpec() {
    @MockkBean(relaxUnitFun = true)
    lateinit var watcher: Watcher

    @MockkBean(relaxUnitFun = true)
    lateinit var metricsCollector: SystemMetricsCollector

    @LocalServerPort
    var port: Int = 0

    init {
        test("the server profile answers the JSON API and starts the watcher") {
            val response =
                RestClient
                    .create("http://localhost:$port")
                    .get()
                    .uri("/api/builds/latest")
                    .retrieve()
                    .toEntity(String::class.java)

            response.statusCode.is2xxSuccessful.shouldBeTrue()
            response.headers.contentType!!
                .isCompatibleWith(MediaType.APPLICATION_JSON)
                .shouldBeTrue()

            verify { watcher.start(any()) }
            verify { metricsCollector.start() }
        }
    }
}
