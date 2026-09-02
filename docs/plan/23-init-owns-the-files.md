# Step 23: Init Owns the Files, `tools/remote` Wraps

Prerequisites: step 21 session D (the role-named `tools/remote`).
Read `README.md` first.

## The Problem

`werkator init` and `tools/remote` overlap: both write the machine config — init as a commented template, the script by appending heredoc blocks (`bwrap`, `server`) and patching values with `sed`.
The script re-implements configuration knowledge Werkator owns (YAML shape, indentation, key names), outside the three-places sync invariant — the indentation-mismatch of one append guard produced nine duplicate `bwrap` blocks on mih34 before it was found.
Smaller duplications of the same kind: the script re-implements control-token generation in bash (`ControlTokenService` owns it), and `check-prerequisites` still pipes the bash script whose generic half exists as `werkdock doctor`.

## The Decision (2026-09-01)

Werkator becomes the executing app wherever possible; `tools/remote` shrinks to a wrapper: build artifacts locally, transport them, execute Werkator/werkdock remotely, switch services.

Parameters travel as **files**, not as many CLI options — and each side gets the format that is native to it (refined 2026-09-01):

- The **wrapper** keeps a small, bash-sourceable env file with the transport values only: `tools/remote --env .env.mih34 werkator repo-init` selects the target (default: `.env`), so several instances (`.env.mih34`, `.env.vm4006`, later a Werkbaum instance) are files, not edits.
- **Werkator** takes a **YAML fragment in its own config schema**: `werkator init --apply mih34.yml` deep-merges the fragment into the machine config, idempotently — creating sections that are missing, updating the given values, never duplicating.
  No mapping table exists: the fragment says `server: {port: …}` and `builds: {default: {bwrap: …}}` directly, is validated by the existing schema binding, and is documented by the existing `docs/configuration.md`.
- The wrapper uploads the fragment alongside the artifacts and calls `werkator init --apply …` remotely — the heredocs and `sed` calls in `tools/remote` disappear.
- The env file names the fragment (`WERKATOR_INIT_CONFIG=mih34.yml`), keeping one entry point per instance.

The removed legacy env-to-YAML conversion stays removed — there is no conversion at all anymore: the fragment already *is* configuration in the one schema, applied once at setup time; the server reads nothing but its YAML at runtime.

## The Files per Instance

- `.env.mih34` (wrapper): `WERKATOR_REMOTE`, `WERKATOR_PATH`, `WERKATOR_LOCAL_PORT`, `WERKATOR_REPO_URL`, `WERKATOR_ROOTFS` (the *local* archive to upload), `WERKATOR_INIT_CONFIG`.
- `mih34.yml` (init fragment): `server.*` (port, publicBaseUrl, systemd limits) and `builds.default.bwrap.*` (enabled, the *remote* rootfs path, werkdock path) — exactly the blocks the script used to append.
- Secrets (`git.token`, `gitea` keys) stay out of both files on purpose — they are entered in the machine config on the host, as today.

## The Sessions

### A — Werkator side

- `init --apply FILE`: deep-merge the YAML fragment into the machine config — reusing the loader's merge, creating missing sections, updating given values, never duplicating (the duplication class dies here); a fragment that fails the schema binding or carries unknown keys is refused loudly.
- `init --systemd` keeps generating the units; decide in the session whether the Apache `.htaccess` becomes part of the host-integration output when the applied config carries `server.port` and a public domain (proposal: yes, under `init --systemd`, since it is generated host integration exactly like the units).
- New subcommand `werkator control-token`: print the token, creating it exactly like `ControlTokenService` does — the bash duplication in the wrapper dies.
- Tests per the writing-tests conventions; `docs/bootstrapping.md` documents `--apply` (the fragment keys need no new reference — they are ordinary `docs/configuration.md` keys).

### B — Wrapper side

- `tools/remote --env FILE` (default `.env`); the init fragment named by `WERKATOR_INIT_CONFIG` is uploaded, and the remote init runs with `--apply`.
- `repo-init` and `instance-start` lose their heredoc/`sed` config writing; `control-token` delegates to the new subcommand.
- `check-prerequisites` uploads the werkdock binary first and runs `werkdock doctor`; `tools/werkator-build-prerequisites.sh` retires (its werkdock port is the survivor).

### C — Live verification and docs

- Run the full wrapper flow against mih34 (`instance-update`, `repo-init`, `instance-start` as no-op re-runs); `docs/deployment.md`'s webspace section switches to the `--env` invocations.

## Acceptance Criteria

- Session A: `werkator init --apply …` merges and re-merges a fragment idempotently; `werkator control-token` exists; full suite green.
- Session B: `tools/remote` contains no YAML heredocs and no `sed` into the machine config; the prerequisites bash script is gone.
- Session C: the mih34 re-runs change nothing on a configured host and the deployment docs show only `--env`-style calls.
