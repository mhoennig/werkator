> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

"One instance per repository" is a founding tenet (`docs/Werkator-Konzept.md`, AGENTS.md), and it does not scale even to two repositories on a Hostsharing Managed Webspace: building Werkbaum next to Werkator on mih34 would need a second service, a second assigned port, a second tunnel or domain, a second UI, a second metrics page.
Every repository added multiplies operations, while the instance-level resources (port, UI, watcher schedule, executor slots, metrics) could be shared.

## Non-Goals

- Implementing the refactor — this PR is the decision and the roadmap only; the sessions it lays out (B–E: `RepoContext` refactor, registry/watcher multiplexing, routes/UI scoping, mih34 rollout with Werkbaum) are future PRs.
- Changing `docs/Werkator-Konzept.md`'s or AGENTS.md's architecture wording — that happens when the implementation lands, not with the decision.

## The Solution

ADR 0009 revises the tenet to "one instance per repository *set*": a Werkator instance aggregates self-contained repositories listed in an instance registry, sharing one service, one port, one UI, one watcher schedule, and one global executor cap, while each repository keeps its own configuration, secrets, history, and artifacts — nothing repository-specific moves out of the repository, so adding or removing one is a registry entry, never a data migration.
Config ownership splits three ways: **instance-level** config (`server.*`, the registry, the control token, the global `executor.maxConcurrent`, watcher schedule) lives in `~/.werkator.yml` in the home directory of the user running the instance — one instance per OS user, matching the platform's pac-user model; the file name stays `.werkator.yml` everywhere, only the location carries the meaning.
**Repo defaults** may live in the same home file, but only in an explicit `defaults:` block merged *below* every repository's own layers (home defaults → committed project config → repo machine config → branch layer) — pinning semantics are unchanged, at the accepted cost that secrets may then live in two places.
**Repo-level** config (`gitea.*`, `git.*` credentials, `builds`, retention, sandbox policy) stays exactly where it is today, in each repository's own layers.
Four follow-on decisions were folded in during review: repo-level instance keys found once a home config exists are ignored with a warning naming both files, never merged silently; when a home config with a registry exists it is served regardless of the current directory (registry wins over cwd); repository names for routes/UI default to the directory basename, are overridable per entry, and duplicates abort the start loudly; the `.werkator.yml` name is kept in all three locations rather than inventing a separate instance-config filename.
A federation dashboard proxying several single-repo instances was considered and rejected: it solves only UI aggregation, not the per-repository operations burden (ports, tunnels, services) that motivates the change.

## Open Questions

- Fairness across repos when the global concurrency cap is contended (round-robin vs. FIFO) — deferred to session C, decided with the real queue behavior at hand.
- Whether buildenv rootfs trees should be shared across repos, or whether that is better solved by Werkdock's image store — deferred; duplicate unpacked rootfs trees are the accepted interim cost.
- Whether `artifactKey` needs a repo prefix or stays globally unique by construction — deferred to session B, when routes are designed.

## Additional Changes

- `docs/plan/22-multi-repo.md`: the full five-session roadmap (A–E).
- AGENTS.md: decision list gained ADR 0009.

## Prerequisite PRs

- None in code; branches from `werkdock-extraction` (PR #6) but is otherwise independent of the Werkdock/webspace work in PR #7/#8/#9.

## Follow-up PRs

- Session B: `RepoContext` refactor, behavior-preserving.
- Session C: the registry and N repositories, watcher multiplexing.
- Session D: server/API/UI repo scoping.
- Session E: rollout on mih34 with Werkbaum joining the instance.
