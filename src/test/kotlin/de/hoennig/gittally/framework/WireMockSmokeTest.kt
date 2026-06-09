package de.hoennig.gittally.framework

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WireMockSmokeTest : FunSpec({

    val server = WireMockServer(options().dynamicPort())

    beforeSpec { server.start() }
    afterSpec { server.stop() }

    test("WireMock stubs an HTTP endpoint") {
        server.stubFor(get("/ping").willReturn(aResponse().withStatus(200).withBody("pong")))

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:${server.port()}/ping")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        response.statusCode() shouldBe 200
        response.body() shouldBe "pong"
    }
})
