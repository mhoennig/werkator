---
name: writing-tests
description: werkator testing conventions — Kotest FunSpec spec structure, MockK matchers, and the two patterns for mocking beans in Spring slice tests (springmockk @MockkBean or @TestConfiguration). Use when writing, extending, or refactoring tests.
---

# Writing Tests for werkator

Tests use **Kotest `FunSpec`** style. `SpringExtension` is registered globally in `io.kotest.provided.ProjectConfig` — do not add it per-spec.

```kotlin
class MyTest : FunSpec() {
    init {
        test("description") { ... }
        beforeEach { ... }
    }
}
```

Use `shouldBe`, `shouldNotBe`, `shouldThrow` etc. from `io.kotest.matchers`.

Tests mirror the production package structure under `src/test/kotlin`.

Run a single test class instead of the full suite while iterating:

```bash
./gradlew test --tests "de.hoennig.werkator.ApplicationContextTest"
```

## Mocking in Spring Slice Tests

Use `@MockkBean` from `springmockk` to inject MockK mocks into the Spring context:

```kotlin
@WebMvcTest(SomeController::class)
class SomeControllerTest : FunSpec() {
    @MockkBean
    lateinit var someService: SomeService
    init {
        beforeEach { clearMocks(someService) }
        // full MockK syntax: every { } / verify { }
    }
}
```

Alternatively, register mocks via `@TestConfiguration` without the springmockk dependency:

```kotlin
@WebMvcTest(SomeController::class)
@Import(SomeControllerTest.Mocks::class)
class SomeControllerTest : FunSpec() {
    @TestConfiguration
    class Mocks {
        @Bean fun someService(): SomeService = mockk()
    }
    @Autowired lateinit var someService: SomeService
    init {
        beforeEach { clearMocks(someService) }
    }
}
```

Pure unit tests (no Spring context) use MockK directly without any Spring wiring.

## Test Infrastructure by Layer

- Git-facing code: integration tests against local fixture repositories (bare origin + clones), see `GitServiceTest` — no network access, hermetic git environment variables.
- HTTP clients (Gitea): WireMock.
- Docker-dependent code: Testcontainers.
- The `Watcher` and other schedulers never start their loops in tests; call `poll()`/lifecycle methods directly.
