# Pull-Request Documentations in doc/PR

This directory contains documentation for each pull request (PR).

IMPORTANT: The PR-documentation documents the change which was applied in that PR.
Such documentation might be outdated right after the next PR was merged.
Historic PR-documentation is not maintained along with new PRs.

## Naming Convention

`YYYY-MM-DD-PR#999-short-description-of-pr`

1. Date of the PR in the format `YYYY-MM-DD`
2. followed '-PR#' followed by the number of the pull request in GitEA,
3. a short description of the PR with dashes between the words,
4. '.md'

Yes, to get the PR-number, you need to open a pull request first, 
but initially prefix its title with `WIP: ` to mark it as a work in progress until it is ready for review.

## Guidelines

- Documentations must be written in Markdown format.
- Use Englisch, it's a public open source project.
- Use clear and concise language and keep it short.
- Include relevant details and context, explain the "why".
- Mark reference to Taiga or any other tool that is not public as "Hostsharing-internal".
- If necessary, copy important parts of the ticket description.
- One sentence or statement per line to make diffs easier to read.

## Structure

The main (`##`) sections have to appear in exactly this order, omitting sections which do not apply:

0. Related Links (optional)
1. The Problem (required)
2. Non-Goals (required)
3. The Scenarios (optional for maintenance or bug fixing PRs, required for features)
4. The Solution (required)
5. Open Questions (optional)
6. Additional Changes (optional)
7. Prerequisite PRs (optional)
8. Follow-up PRs (optional)
9. Attachments (optional)

For details, see [template](TEAMPLATE.md)
