# GitTally — Agent Instructions

This file is read by Claude Code (`CLAUDE.md`) and other AI coding agents (`AGENTS.md → CLAUDE.md`).

## Build and Test Commands

```bash
./gradlew build          # compile + ktlintCheck + test
./gradlew ktlintFormat   # auto-format before committing
./gradlew test           # run all tests (can be slow, prefer single test)
./gradlew test --tests "de.hoennig.gittally.ApplicationContextTest"  # example for running a single test class
```

Run the JAR directly:

```bash
java -jar build/libs/gittally-0.1.0-SNAPSHOT.jar --help
java -jar build/libs/gittally-0.1.0-SNAPSHOT.jar init
```

`ktlintFormat` must be run before `build` passes — the formatter is enforced as part of the `check` lifecycle.

## Architecture

GitTally is a dual-mode application: **CLI** (interactive, status, config) and **Server** (HTTP, persistent). It is currently CLI-only; the server mode is not yet implemented.

### Entry Point and CLI Wiring

Spring Boot starts via `GitTallyApplication`. A separate `CliRunner` component (in the same file) implements both `CommandLineRunner` (runs picocli) and `ExitCodeGenerator` (returns the exit code). `exitProcess` is called only from `main()` via `SpringApplication.exit()` — **never** inside `run()`. This keeps the Spring context alive during tests.

Picocli commands are Spring `@Component` beans. The root command (`GitTallyCommand`) declares subcommands as class references in `@Command(subcommands = [...])`. Picocli resolves them from the Spring context via the auto-configured `IFactory` bean.

```
GitTallyApplication   ← @SpringBootApplication
CliRunner             ← CommandLineRunner + ExitCodeGenerator
GitTallyCommand       ← root @Command, delegates to subcommands
commands/
  InitCommand         ← "init"
  ServerCommand       ← "server"
  ConfigPrintCommand  ← "config:print [--full]"
```

The web application type is set to `none` in `application.yml`. The `server` subcommand will need to restart the context with a web type when implemented.

### Package Structure

All production code lives under `de.hoennig.gittally`. Commands are in the `commands` sub-package. Tests mirror this structure under `src/test/kotlin`.

## Testing Conventions

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

### Mocking in Spring Slice Tests

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

## File-Formatting

### Markdown

Write documentation in English in Markdown files.
In Markdown, use a single line per sentence.
Keep sentences short.

## Key Architectural Decisions

All major decisions are in `docs/adrs/`. Run `adr-status` (after `source .envrc`) for a one-line summary of each. Decisions in force:

- **Test framework**: Kotest + MockK + WireMock + Testcontainers (ADR 0001)
- **Gradle**: 8.14.5 (ADR 0002)
- **Spring Boot**: 4.0.6 (ADR 0003)
