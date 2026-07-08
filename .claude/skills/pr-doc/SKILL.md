---
name: pr-doc
description: Write or update the pull-request documentation file (PR-doc) under docs/prs/ for the current change. Use when preparing or opening a pull request, when the user asks for a PR-doc, or after finishing a feature that will become a PR — every PR needs one.
---

# Writing a PR-Doc

Every pull request needs one documentation file in `docs/prs/`.
The authoritative convention is `docs/prs/README.md` — read it before writing.
Copy the section skeleton from `docs/prs/TEAMPLATE.md`.

## Filename

`YYYY-MM-DD-PR#<number>-short-description-of-pr.md`

- Date of the PR, then `-PR#` and the Gitea PR number, then a dash-separated short description, then `.md`.
- The PR number requires an open pull request; open it early with a `WIP: ` title prefix to reserve the number.
- If the PR is not opened yet, use `PR#000` as a placeholder in the filename and in scenario IDs, and remind the user to rename both once the number exists.

## Structure

The main (`##`) sections must appear in exactly this order, omitting sections that do not apply:

1. The Problem
2. Non-Goals
3. The Scenarios
4. The Solution
5. Open Questions
6. Additional Changes
7. Prerequisite PRs
8. Follow-up PRs

A `Related Links` section may precede `The Problem`; an `Attachments` section may follow at the very end.
Do not reorder already-merged PR-docs.

## Writing Rules

- Keep the snapshot disclaimer blockquote from the template at the top of every PR-doc.
- English, Markdown, one sentence per line, keep it short.
- Explain the "why", not just the "what".
- Scenarios use Markdown-native pseudo-Gherkin (no fenced Gherkin blocks) with IDs `Scenario#<pr-number>.<nn>`.
- Each scenario gets a `##### Verified by` list linking the tests that cover it (relative links from `docs/prs/`).
- Mark references to Taiga or other non-public tools as "Hostsharing-internal".
- PR-docs document the change of that PR at that time; do not maintain historic PR-docs when later PRs change the behavior.
