package de.hoennig.gittally.gitea

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.config.GitConfig
import de.hoennig.gittally.config.GitTallyConfig
import de.hoennig.gittally.config.GiteaConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

class GiteaClientTest :
    FunSpec({

        val server = WireMockServer(options().dynamicPort())
        val configLoader = mockk<ConfigLoader>()
        val client = GiteaClient(configLoader)

        val publishUrl = "/api/v1/repos/the-owner/the-repo/statuses/abc123"
        val readUrl = "/api/v1/repos/the-owner/the-repo/commits/abc123/statuses?sort=recentupdate"

        fun configure(
            baseUrl: String = "http://localhost:${server.port()}",
            owner: String = "the-owner",
            repo: String = "the-repo",
            token: String = "secret-token",
        ) {
            every { configLoader.load(any()) } returns
                GitTallyConfig(
                    git = GitConfig(token = token),
                    gitea = GiteaConfig(baseUrl = baseUrl, owner = owner, repo = repo, statusContext = "GitTally"),
                )
        }

        beforeSpec { server.start() }
        afterSpec { server.stop() }
        beforeEach {
            server.resetAll()
            configure()
        }

        context("isEnabled") {
            test("is true when baseUrl, owner, repo, and token are configured") {
                client.isEnabled() shouldBe true
            }

            test("is false when any required value is missing") {
                configure(baseUrl = "")
                client.isEnabled() shouldBe false
                configure(owner = "")
                client.isEnabled() shouldBe false
                configure(repo = "")
                client.isEnabled() shouldBe false
                configure(token = "")
                client.isEnabled() shouldBe false
            }
        }

        context("publishStatus") {
            test("posts state, context, description, and target_url with token auth") {
                server.stubFor(post(publishUrl).willReturn(aResponse().withStatus(201)))

                val published =
                    client.publishStatus(
                        sha = "abc123",
                        status = BuildStatus.SUCCESS,
                        description = "Build succeeded",
                        targetUrl = "https://ci.example.org/branches/main/index.html",
                    )

                published shouldBe true
                server.verify(
                    postRequestedFor(urlEqualTo(publishUrl))
                        .withHeader("Authorization", equalTo("token secret-token"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withRequestBody(
                            equalToJson(
                                """
                                {
                                  "state": "success",
                                  "context": "GitTally",
                                  "description": "Build succeeded",
                                  "target_url": "https://ci.example.org/branches/main/index.html"
                                }
                                """.trimIndent(),
                            ),
                        ),
                )
            }

            test("omits target_url when none is given") {
                server.stubFor(post(publishUrl).willReturn(aResponse().withStatus(201)))

                client.publishStatus("abc123", BuildStatus.RUNNING, "Build running") shouldBe true

                server.verify(
                    postRequestedFor(urlEqualTo(publishUrl))
                        .withRequestBody(
                            equalToJson("""{"state": "pending", "context": "GitTally", "description": "Build running"}"""),
                        ),
                )
            }

            test("maps every build status to the documented Gitea state") {
                server.stubFor(post(publishUrl).willReturn(aResponse().withStatus(201)))
                val expectedStates =
                    mapOf(
                        BuildStatus.SUCCESS to "success",
                        BuildStatus.FAILED to "failure",
                        BuildStatus.CANCELLED to "failure",
                        BuildStatus.PENDING to "pending",
                        BuildStatus.RUNNING to "pending",
                        // interrupted builds are re-enqueued on startup, so the commit must not turn red
                        BuildStatus.INTERRUPTED to "pending",
                    )

                expectedStates.forEach { (status, state) ->
                    server.resetRequests()
                    client.publishStatus("abc123", status, "d") shouldBe true
                    server.verify(
                        postRequestedFor(urlEqualTo(publishUrl))
                            .withRequestBody(equalToJson("""{"state": "$state", "context": "GitTally", "description": "d"}""")),
                    )
                }
            }

            test("returns false without a request when disabled") {
                configure(token = "")

                client.publishStatus("abc123", BuildStatus.SUCCESS, "d") shouldBe false

                server.findAll(postRequestedFor(urlEqualTo(publishUrl))).size shouldBe 0
            }

            test("returns false on HTTP errors instead of throwing") {
                server.stubFor(post(publishUrl).willReturn(aResponse().withStatus(500)))

                client.publishStatus("abc123", BuildStatus.SUCCESS, "d") shouldBe false
            }

            test("returns false when Gitea is unreachable instead of throwing") {
                configure(baseUrl = "http://localhost:1")

                client.publishStatus("abc123", BuildStatus.SUCCESS, "d") shouldBe false
            }
        }

        context("readStatus") {
            test("requests statuses sorted by recentupdate with token auth") {
                server.stubFor(get(readUrl).willReturn(okJson("[]")))

                client.readStatus("abc123")

                server.verify(
                    getRequestedFor(urlEqualTo(readUrl))
                        .withHeader("Authorization", equalTo("token secret-token")),
                )
            }

            test("returns the newest status matching the configured context") {
                server.stubFor(
                    get(readUrl).willReturn(
                        okJson(
                            """
                            [
                              {"context": "other-ci", "state": "failure"},
                              {"context": "GitTally", "state": "success", "description": "Build succeeded"},
                              {"context": "GitTally", "state": "pending"}
                            ]
                            """.trimIndent(),
                        ),
                    ),
                )

                client.readStatus("abc123") shouldBe GiteaStatusResult.Found(BuildStatus.SUCCESS)
            }

            test("returns None when no status matches the context") {
                server.stubFor(get(readUrl).willReturn(okJson("""[{"context": "other-ci", "state": "success"}]""")))

                client.readStatus("abc123") shouldBe GiteaStatusResult.None
            }

            test("returns None for an empty status list") {
                server.stubFor(get(readUrl).willReturn(okJson("[]")))

                client.readStatus("abc123") shouldBe GiteaStatusResult.None
            }

            test("returns Error for malformed JSON instead of throwing") {
                server.stubFor(get(readUrl).willReturn(okJson("""{"oops": "not a list"!!!""")))

                client.readStatus("abc123").shouldBeInstanceOf<GiteaStatusResult.Error>()
            }

            test("returns Error for an unknown state value") {
                server.stubFor(get(readUrl).willReturn(okJson("""[{"context": "GitTally", "state": "hovering"}]""")))

                client.readStatus("abc123").shouldBeInstanceOf<GiteaStatusResult.Error>()
            }

            test("returns Error on HTTP 4xx/5xx instead of throwing") {
                server.stubFor(get(readUrl).willReturn(aResponse().withStatus(404)))
                client.readStatus("abc123").shouldBeInstanceOf<GiteaStatusResult.Error>()

                server.stubFor(get(readUrl).willReturn(aResponse().withStatus(500)))
                client.readStatus("abc123").shouldBeInstanceOf<GiteaStatusResult.Error>()
            }

            test("returns Error when Gitea is unreachable instead of throwing") {
                configure(baseUrl = "http://localhost:1")

                client.readStatus("abc123").shouldBeInstanceOf<GiteaStatusResult.Error>()
            }

            test("returns Disabled when unconfigured, without a request") {
                configure(owner = "")

                client.readStatus("abc123") shouldBe GiteaStatusResult.Disabled

                server.findAll(getRequestedFor(urlEqualTo(readUrl))).size shouldBe 0
            }
        }

        context("resolveUsername") {
            test("returns the login of the token's user") {
                server.stubFor(get("/api/v1/user").willReturn(okJson("""{"id": 42, "login": "the-user"}""")))

                client.resolveUsername() shouldBe "the-user"

                server.verify(
                    getRequestedFor(urlEqualTo("/api/v1/user"))
                        .withHeader("Authorization", equalTo("token secret-token")),
                )
            }

            test("only needs baseUrl and token, not owner/repo") {
                configure(owner = "", repo = "")
                server.stubFor(get("/api/v1/user").willReturn(okJson("""{"login": "the-user"}""")))

                client.resolveUsername() shouldBe "the-user"
            }

            test("returns null when baseUrl or token is missing") {
                configure(baseUrl = "")
                client.resolveUsername().shouldBeNull()

                configure(token = "")
                client.resolveUsername().shouldBeNull()
            }

            test("returns null on HTTP errors, missing login, or malformed JSON instead of throwing") {
                server.stubFor(get("/api/v1/user").willReturn(aResponse().withStatus(401)))
                client.resolveUsername().shouldBeNull()

                server.stubFor(get("/api/v1/user").willReturn(okJson("""{"id": 42}""")))
                client.resolveUsername().shouldBeNull()

                server.stubFor(get("/api/v1/user").willReturn(okJson("not json")))
                client.resolveUsername().shouldBeNull()
            }
        }
    })
