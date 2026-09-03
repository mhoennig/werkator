> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

Werkdock was grown inside this repository on purpose — plan step 21, sessions B and C — and the plan said from the first line that it "moves to its own repository later".
Everything that move needs has been true since session C: the tool has no imports from Werkator code, no Gradle coupling, and Werkator reaches it the way it reaches `git` — as a binary on the `PATH`, named by the pinned key `bwrap.werkdock`.

What kept it here was three references, not a dependency:
`.werkator.yml` carried a second build definition for a Go module that has nothing to do with the Kotlin build around it,
`tools/remote` built the binary out of the subdirectory,
and the documentation described a directory that was about to stop existing.

The instance makes the cost visible: every Werkator branch built a `<branch>@werkdock` artifact, so Werkdock was rebuilt for changes that could not possibly affect it, and its own history was invisible under Werkator's branch names.

## Non-Goals

- Changing Werkdock itself: the extracted repository holds the same nine commits, byte for byte, only without the path prefix.
- Changing how Werkator uses Werkdock: `bwrap.enabled`, `bwrap.rootfs` and `bwrap.werkdock` stay as they are, still pinned, still resolved via `PATH` at run time.
- Publishing releases of the binary: `tools/remote` keeps installing the locally built one (ADR 0006 — never built on the target).
- Registering the new repository on the instance: that is one `tools/remote werkator repo-add` plus a registry entry, done in the rollout, not in this branch.

## The Solution

**The history moves with the files.**
`git subtree split -P werkdock` lifts the nine commits that touched the subdirectory into a root-level history; the new repository starts from that, so `git log` and `git blame` keep working across the move.
A plain copy would have made this PR cheaper and the tool's past unreadable.

**The build definition follows, and becomes the only one.**
What was `builds.werkdock` here is `builds.default` in the new repository — same commands, minus the `cd werkdock` prefix, same `gofmt`-twice idiom (the first call names the unformatted files, the second fails the build on them).
This repository's `.werkator.yml` is back to one definition, and a Werkator branch no longer rebuilds a Go module it cannot influence.

**`tools/remote` asks for a checkout instead of a subdirectory.**
`WERKDOCK_REPO` names it (default: a sibling of this repository — the usual layout when you work on both), `WERKDOCK_BINARY` the built binary within it, and both are overridable from the environment like every other transport value.
A missing checkout now fails with the clone URL in the message rather than a bare "file not found": the script cannot fix the situation itself any more, so it says what would.

**The documentation stops describing a subdirectory.**
`docs/deployment.md` links the repository instead of `../werkdock/README.md`, the plan index and the architecture skill say the extraction happened, and plan step 21 gains session E with what was decided and what it left alone.

## Verification

- `./gradlew ktlintFormat build` green in this repository without `werkdock/`.
- The extracted repository builds and tests green on its own — `gofmt`, `go vet`, `go test ./...` (four packages), `go build` — which is the acceptance criterion the plan set for a self-contained tool.
- `bash -n tools/remote`, and the sibling-checkout default resolved against a real checkout.

## Open Questions

**A branch cannot remove a build definition — only add or override one.**
Observed on this very branch: the instance built `21e-werkdock-own-repo@werkdock` and it failed, although the branch's committed `.werkator.yml` no longer defines `werkdock`.
The cause is not a bug in the watcher — it reads the branch layer, and no fallback warning was logged — but the merge itself:
`withBranchLayer` does `deepMerge(loadRaw(workingDir), stripPinned(branchLayer))`, so a definition present in the project layer and absent on the branch survives the merge.
`AGENTS.md` and the architecture skill describe the branch layer as winning "including the whole `builds` section", which reads as replacement.

The mismatch resolves itself on merge, but one commit later than one would guess, and the watcher log shows exactly where:
at the merge commit `ac53b5f` both `default` and `werkdock` were still enqueued, at the next commit only `default`.
The project layer is the `.werkator.yml` in the *watched clone's working tree*, so the definition survives until that tree has advanced past its removal — the merge itself still gets one last stray build.
It does not block this PR.
It is a decision, not an oversight to fix in passing: either `builds` is replaced as a whole (then a branch can retire a build, and a branch that only adds one must repeat the others), or the merge stays and the two documents are corrected to say that removal is not expressible on a branch.
