> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

`werkator init` and `tools/remote` overlap: both write the machine config — init as a commented template, the script by appending heredoc blocks and patching values with `sed`.
The script re-implements configuration knowledge Werkator already owns (YAML shape, indentation, key names) outside the three-places sync invariant; an indentation mismatch in one append guard produced nine duplicate `bwrap` blocks on mih34 (step 21 session D) before it was found.
Smaller duplications of the same kind: the script re-implements control-token generation in bash, and `check-prerequisites` still pipes a bash script whose generic half now exists as `werkdock doctor`.

## Non-Goals

- Implementing the change — this PR is the plan only; PR #9 implements it.
- Multi-repository support for one Werkator instance (step 22, PR #10).

## The Solution

`docs/plan/23-init-owns-the-files.md` records the decision: Werkator becomes the executing app wherever possible, `tools/remote` shrinks to a wrapper.
Parameters travel as files, each side getting the format native to it: the wrapper keeps a small env file with transport-only values (`--env-file FILE`, mirroring Docker's flag naming since `--env` there means a single variable); Werkator takes a YAML fragment in its own config schema, applied via a new `init --apply FILE`, validated by the existing schema binding and needing no separate mapping table.
The plan was refined once during review: the first sketch proposed an env-file-only transport; the fragment being a first-class YAML file in Werkator's own schema replaced that, so there is no env-key-to-config-key conversion table to maintain at all.
Three sessions are laid out: A (Werkator side: `init --apply`, `control-token` subcommand), B (wrapper side: `tools/remote` loses its heredocs), C (live verification on mih34 and doc updates).

## Prerequisite PRs

- PR #7 (webspace install path) — this plan corrects the remaining duplication that PR left in place.

## Follow-up PRs

- PR #9: implements sessions A, B, and C of this plan.
