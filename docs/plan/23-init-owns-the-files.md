# Step 23: Init Owns the Files, `tools/remote` Wraps

Prerequisites: step 21 session D (the role-named `tools/remote`).
Read `README.md` first.

## The Problem

`werkator init` and `tools/remote` overlap: both write the machine config — init as a commented template, the script by appending heredoc blocks (`bwrap`, `server`) and patching values with `sed`.
The script re-implements configuration knowledge Werkator owns (YAML shape, indentation, key names), outside the three-places sync invariant — the indentation-mismatch of one append guard produced nine duplicate `bwrap` blocks on mih34 before it was found.
Smaller duplications of the same kind: the script re-implements control-token generation in bash (`ControlTokenService` owns it), and `check-prerequisites` still pipes the bash script whose generic half exists as `werkdock doctor`.

## The Decision (2026-09-01)

Werkator becomes the executing app wherever possible; `tools/remote` shrinks to a wrapper: build artifacts locally, transport them, execute Werkator/werkdock remotely, switch services.

Parameters travel as an **env file**, not as many CLI options:

```bash
tools/remote --env .env.mih34 werkator repo-init
werkator --env .env.mih34 init
```

- `tools/remote --env FILE` selects the target (default: `.env`), so several instances (`.env.mih34`, `.env.vm4006`, later a Werkbaum instance) are files, not edits.
- `werkator --env FILE` loads the same file; `init` reads the `WERKATOR_*` values from it (or from the process environment) and writes **real values** into the files it owns, instead of commented templates the script then patches.
- The wrapper uploads the env file alongside the artifacts and calls `werkator --env … init` remotely — the heredocs and `sed` calls in `tools/remote` disappear.

Not a relapse into the removed legacy env-to-YAML conversion: the env file is an *input to init at setup time*, written once into the machine config — the server never reads `WERKATOR_*` at runtime, and the YAML stays the single source of truth.

## Env Keys and Their Targets

Consumed by `werkator init` (written into the machine config; re-runs update these managed values in place, schema-aware instead of `sed`):

| Env key | Config key |
|---|---|
| `WERKATOR_PORT` | `server.port` |
| `WERKATOR_DOMAIN` | `server.publicBaseUrl` (`https://<domain>/`) |
| `WERKATOR_MEMORY_MAX` / `WERKATOR_TASKS_MAX` | `server.systemd.memoryMax` / `tasksMax` |
| `WERKATOR_ROOTFS` (remote path) | `builds.default.bwrap.rootfs` (+ `bwrap.enabled: true`) |
| `WERKATOR_WERKDOCK` | `builds.default.bwrap.werkdock` |

Transport-only keys (`WERKATOR_REMOTE`, `WERKATOR_PATH`, `WERKATOR_LOCAL_PORT`, `WERKATOR_REPO_URL`) stay the wrapper's business; init ignores them, documented.
Secrets (`git.token`, `gitea` keys) stay out of env files on purpose — they are entered in the machine config on the host, as today.

## The Sessions

### A — Werkator side

- Root-level picocli option `--env FILE`: loads `KEY=VALUE` lines into the command's parameter environment; unknown keys are ignored (they belong to the wrapper).
- `init` writes real values for the keys above when they are set — creating the sections when missing, updating the managed values when present, never duplicating (the duplication class dies here).
- `init --systemd` keeps generating the units; decide in the session whether the Apache `.htaccess` becomes part of the host-integration output when `WERKATOR_PORT`/`WERKATOR_DOMAIN` are set (proposal: yes, under `init --systemd`, since it is generated host integration exactly like the units).
- New subcommand `werkator control-token`: print the token, creating it exactly like `ControlTokenService` does — the bash duplication in the wrapper dies.
- Tests per the writing-tests conventions; `docs/configuration.md` and `docs/bootstrapping.md` document the env keys.

### B — Wrapper side

- `tools/remote --env FILE` (default `.env`); the file is uploaded and every remote `werkator` call gets `--env`.
- `repo-init` and `instance-start` lose their heredoc/`sed` config writing; `control-token` delegates to the new subcommand.
- `check-prerequisites` uploads the werkdock binary first and runs `werkdock doctor`; `tools/werkator-build-prerequisites.sh` retires (its werkdock port is the survivor).

### C — Live verification and docs

- Run the full wrapper flow against mih34 (`instance-update`, `repo-init`, `instance-start` as no-op re-runs); `docs/deployment.md`'s webspace section switches to the `--env` invocations.

## Acceptance Criteria

- Session A: `werkator --env … init` writes and updates the managed config values idempotently; `werkator control-token` exists; full suite green.
- Session B: `tools/remote` contains no YAML heredocs and no `sed` into the machine config; the prerequisites bash script is gone.
- Session C: the mih34 re-runs change nothing on a configured host and the deployment docs show only `--env`-style calls.
