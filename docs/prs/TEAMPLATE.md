
## The Problem

A prosa description of the problem, which this PR is supposed to solve.

## Non-Goals

What this PR deliberately does not do, to delimit the scope.

## The Scenarios

A schematized specification of the requirements, preferably using [Gherkin](https://cucumber.io/docs/gherkin/reference/) vocabulary.
But Gherkin does not make sense for all kinds of PRs.

Use Markdown-native pseudo-Gherkin instead of fenced Gherkin code blocks.
This keeps the scenarios linkable and allows direct links to tests, issues, ADRs, or explanations inside the requirement text.

### Feature: headline of the feature

#### Background

- definitions of terms
- other background information

#### Scenario#236.01: Description of a requirement in the shape of a scenario!

So that ... (describe the goal behind the requirement here).

- **Given** some precondition
  - **and** another precondition
- **When** whatever is done
- **Then** postcondition
  - **and** another postcondition

##### Verified by

- [ExampleScenarioTests.exampleScenario](../../src/test/java/.../ExampleScenarioTests.java)

#### Scenario#236.02: Description of another requirement in the shape of a scenario!

...

Such feature descriptions are also very helpful in deriving tests and can lead agentic coding AI very well.

## The Solution

Here you describe the changes you made and why you made them, along with reasoning why they were necessary.
If necessary, you can link to an ADR (Architecture Decision Record).
Keep it short!

## Open Questions

Here you list decisions which are deliberately left open for the reviewer or a follow-up,
each with the currently implemented behavior.
Usually a bullet-list. Keep it short!

## Additional Changes

Here you list any additional changes you made, e.g. "fixed formatting in ..." or "fixed some naming issues".
Usually a bullet-list. Keep it short!

## Prerequisite PRs

Here you list PRs this PR builds upon.

## Follow-up PRs

Here you list work which is intentionally deferred to later PRs.

## Attachments

Here you can add any longer sections that would interrupt the reading flow in the previous sections.
Put each attachment on a level-3 heading ('### ...').

