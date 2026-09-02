> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

`tools/remote` wrote the machine config by appending heredoc blocks and patching values with `sed`, duplicating configuration knowledge (YAML shape, indentation, key names) that `WerkatorConfig` already owns — an indentation mismatch in one append guard produced nine duplicate `bwrap` blocks on mih34 before it was found (step 21 session D).
The script also re-implemented control-token generation in bash and still piped a prerequisites bash script whose generic half now exists as `werkdock doctor`.
PR #8 laid out the fix; this PR implements it.

## Non-Goals

- Multi-repository support for one Werkator instance (step 22, PR #10).
- A mapping table between env keys and config keys — deliberately absent, see The Solution.

## The Scenarios

### Feature: `werkator init --apply` installs an instance config fragment

#### Background

- The applied fragment is a fourth config layer: project `.werkator.yml` → applied fragment → repo-install machine config (secrets, always wins) → branch layer.
- It is strictly validated (unknown keys rejected) and installed verbatim, never merged in place — an in-place merge would re-serialize and destroy the machine config's comments and secrets.

#### Scenario#9.01: A valid fragment becomes its own layer, above the project config and below the machine config

So that an instance-specific setting (e.g. `server.port`) takes effect without touching the committed project config or the machine config's secrets.

- **Given** a project `.werkator.yml` and a machine config with a secret
- **When** `init --apply FILE` installs a fragment that also sets a key the machine config sets
- **Then** the effective config shows the fragment's value where the machine config is silent, and the machine config's value where both set the same key.

##### Verified by

- [the applied instance fragment layers above the project config and below the machine config](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)
- [--apply installs the fragment as the applied layer and the effective config sees it](../../src/test/kotlin/de/hoennig/werkator/commands/InitCommandTest.kt)

#### Scenario#9.02: Re-applying a fragment replaces it, never duplicates it

So that repeated `instance-update` runs stay idempotent — the duplication class PR #8 was written against dies here structurally.

- **Given** a fragment already installed as the applied layer
- **When** `init --apply` runs again with a changed fragment
- **Then** the applied layer file is atomically replaced, not appended to.

##### Verified by

- [applyInstanceFragment installs a valid fragment verbatim, and re-applying replaces it](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)

#### Scenario#9.03: An invalid fragment is refused loudly and installs nothing

So that a typo in a fragment never becomes a silent no-op or a half-applied layer.

- **Given** a fragment with an unknown key, or a missing/empty file
- **When** `init --apply FILE` runs
- **Then** it fails loudly and the applied layer is left exactly as it was before the attempt.

##### Verified by

- [applyInstanceFragment refuses an unknown key loudly instead of installing a silent no-op](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)
- [applyInstanceFragment refuses a missing or empty fragment](../../src/test/kotlin/de/hoennig/werkator/config/ConfigLoaderTest.kt)
- [--apply with an invalid fragment installs nothing](../../src/test/kotlin/de/hoennig/werkator/commands/InitCommandTest.kt)

#### Scenario#9.04: `init --systemd` generates the Apache reverse-proxy file alongside the units

So that host integration for a public domain is generated exactly like the systemd units, not hand-written.

- **Given** an effective config with `server.publicBaseUrl` set
- **When** `init --systemd` runs
- **Then** `werkator.htaccess` is generated proxying to the configured localhost port, for the wrapper to place in the domain docroot.

##### Verified by

- [the htaccess proxies everything to the configured localhost port](../../src/test/kotlin/de/hoennig/werkator/commands/SystemdServiceFilesTest.kt)

#### Scenario#9.05: `werkator control-token` prints the same token the server would create

So that the wrapper's bash re-implementation of token generation is no longer needed.

- **Given** a repository with or without an existing control token
- **When** `werkator control-token` runs
- **Then** it creates the token exactly like `ControlTokenService` would and prints the same value on a re-run
  - **and** it fails with exit code 2 outside a repository.

##### Verified by

- [creates the token like the server would and prints the same one on a re-run](../../src/test/kotlin/de/hoennig/werkator/commands/ControlTokenCommandTest.kt)
- [fails with exit code 2 outside a repository](../../src/test/kotlin/de/hoennig/werkator/commands/ControlTokenCommandTest.kt)

### Feature: `tools/remote` becomes a thin wrapper

#### Scenario#9.06: The wrapper selects its instance by file, not by many options

So that several instances (mih34, vm4006, a future Werkbaum instance) are files, not edits to one script.

- **Given** a transport env file (default `.env`) naming a config fragment via `WERKATOR_INIT_CONFIG`
- **When** `tools/remote --env-file .env.mih34 werkator repo-init` runs
- **Then** the fragment is uploaded and `werkator init --apply` is invoked remotely with it — no heredoc or `sed` writes the machine config.

##### Verified by

- live run on mih34 with `.env.mih34` + `.env.mih34.yml`: `instance-update`, `repo-init`, `instance-start` as idempotent re-runs (shell script; no automated test harness for `tools/remote`)

#### Scenario#9.07: `check-prerequisites` delegates to `werkdock doctor`

So that the generic host-readiness checks are not duplicated between a bash script and Werkdock's own porting of it.

- **Given** a target host
- **When** `tools/remote werkator check-prerequisites` runs
- **Then** it uploads the `werkdock` binary and runs `werkdock doctor`, and `tools/werkator-build-prerequisites.sh` is deleted.

##### Verified by

- live run on mih34: doctor-based check-prerequisites, PASS 6/6

## The Solution

Session A (Werkator): `ConfigLoader` reads a new `ConfigFiles.APPLIED` path (`.git/werkator/.werkator.applied.yml`) as a layer between project and repo-install config; `applyInstanceFragment(workingDir, fragment)` validates the fragment against a strict Jackson mapper (`FAIL_ON_UNKNOWN_PROPERTIES=true`, fragment validation only — the regular mapper stays lenient for forward-compatibility) and then copies it verbatim via an atomic move, never merging in place.
`InitCommand` gained `--apply FILE`, applied before `--systemd` handling so generated units/htaccess see the fragment; `SystemdServiceFiles.htaccessContent(port)` is the new generated file, written when `server.publicBaseUrl` is non-blank.
`ControlTokenCommand` is a new subcommand delegating to the existing `ControlTokenService`.
Session B (wrapper): `tools/remote --env-file FILE` (default `.env`) replaced positional/flag-heavy invocation; `repo-init`/`instance-start` lost their heredoc/`sed` config writing in favor of uploading the named fragment and calling `werkator init --apply`; port lookups (`require_idle`, `port_forward`) now parse `werkator config:print` output instead of grepping the machine config file directly, so a port living in the applied fragment is found too; `tools/werkator-build-prerequisites.sh` is deleted.
Session C: verified live end-to-end on mih34 with a `.env.mih34` + `.env.mih34.yml` pair.

Deviation from the PR #8 plan: the fragment is not deep-merged into the machine config as first sketched — it is installed as its own verbatim layer, because an in-place merge would re-serialize the machine config and destroy its comments and secrets; a verbatim copy also makes re-apply a plain file replacement instead of a merge algorithm.

## Open Questions

- Applying a fragment that carries `builds.default` without triggers logs the loader's "no build defines onPush" warning, even though a fragment is judged out of context — cosmetic, fix when it annoys.

## Additional Changes

- `docs/configuration.md`: the layer table now shows four layers; a new subsection documents the applied instance fragment.
- `docs/bootstrapping.md`: a new section documents `--apply`.
- `docs/deployment.md`: the webspace section shows only `--env-file`-style invocations.
- `.gitignore`: `/.env.*` added for per-instance env files.

## Prerequisite PRs

- PR #7 (webspace install path) — the role-separated `tools/remote` this PR simplifies.
- PR #8 (step 23 plan) — the decision this PR implements.

## Follow-up PRs

- PR #10: multi-repository support for one Werkator instance (step 22).
