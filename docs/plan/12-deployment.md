# Step 12: Deployment and Legacy Migration

Prerequisites: steps 07, 08, 10.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Make the new Werkator deployable as a service and retire the legacy script.

## Design

Deployment (documentation plus a small generator, no self-install):

- Extend `init` (or add `init --systemd`) to generate a systemd user unit running `java -jar werkator.jar server` with `WorkingDirectory` set to the repo, `Restart=always`, and an `EnvironmentFile` for overrides — port the shape of the legacy unit, drop the self-copy/update machinery.
- Write `docs/deployment.md`: JRE requirement, jar location convention, systemd enable/start/log commands, and reverse-proxy guidance (example nginx `server` block proxying to `server.port`; TLS via the host's existing certbot — replaces the legacy managed nginx container).

Migration:

- Write `docs/migration-from-legacy.md`: mapping table legacy env vars → YAML keys (source: `00-legacy-analysis.md` and legacy `--env` output), what is intentionally not ported, and the manual steps (stop legacy service, run `init`, fill in token, install new service).
- Decide and document: no automatic import of `build-results.tsv` (history starts fresh) unless trivially cheap.

Housekeeping:

- Mark `legacy/werkator` as deprecated in its header comment and in `README.md`.
- Review `../Werkator-Konzept.md` against what was actually built; update or note deviations.
- Add an ADR summarizing the architecture decisions that emerged during the rewrite (persistence choice, polling UI, no nginx management).

## Tests

- Unit test for the systemd unit generator (content assertions, no systemd interaction).
- Docs have no test, but verify every command in them by running it once.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- A fresh clone can follow `docs/deployment.md` to a running service (manual walkthrough; document the result in this file).

## Implementation Notes (2026-07-07)

Implemented as designed: `init --systemd` (an option on `init`, not a separate subcommand) generates the unit and its `EnvironmentFile` under `.git/werkator/`, prints the install commands, and never touches `~/.config/systemd` itself (no self-install).
`SystemdServiceFiles` builds the file contents and is unit-tested by content assertions, including the legacy `%` escaping and `ExecStart` quoting.
`docs/deployment.md` and `docs/migration-from-legacy.md` were written; `README.md`, `docs/bootstrapping.md`, `../Werkator-Konzept.md`, and `CLAUDE.md` were updated to reference them.
`docs/migration-from-legacy.md` was deleted again on 2026-08-30 with the rename to Werkator: every host it addressed had long since moved to the YAML configuration.

Deviations and decisions:

- The unit is named per repository (`werkator-<repo-name>.service`) instead of the global legacy `werkator.service`, because one instance serves one repository and several repositories can share a host.
- `ExecStart` uses the `java` binary and the jar path of the JVM that ran `init --systemd`, so the unit points at the jar in place (legacy copied the script to an install dir); systemd expands `$JAVA_OPTS` from the `EnvironmentFile` into the command line.
  When not started via `java -jar` (e.g. from Gradle), `init --systemd` prints an error instead of generating a broken unit.
- The `EnvironmentFile` only tunes the JVM (`JAVA_OPTS`); the legacy env file carried username/token, which now live in `.git/werkator/.werkator.yml`.
  An existing `werkator.env` is kept; the unit file is regenerated on every run (same as legacy).
- The legacy `--nginx --docker` `ExecStart` flags were dropped (runtime selection is per-branch config now); the `After=… docker.service` ordering was kept.
- Legacy build history (`build-results.tsv`) is not imported — decided and documented in `docs/migration-from-legacy.md` (formats differ substantially; retention would prune imported rows quickly).
- ADR 0004 records the rewrite architecture decisions (JSON-file persistence behind a repository interface, polling UI, no managed nginx).
- `../Werkator-Konzept.md` review: only one real deviation found — "Builds laufen in Docker" became "nativ oder optional in Docker (pro Branch konfigurierbar)"; CLI capability lists gained build/retry; deployment links added.

Manual walkthrough (2026-07-07, fresh clone under `~/.cache`):

- Followed `docs/deployment.md` end to end: built the jar, copied it to a stable path, cloned the repository freshly, ran `init` and `init --systemd`, linked the generated unit, `daemon-reload`, started the service.
- The clone already contained the committed `.werkator.yml`, so only the machine config was created; `server.port` was overridden to a free port via `.git/werkator/.werkator.yml` to avoid clashing with a locally running instance.
- Result: unit `active (running)`, `GET /` returned 200, `/api/watcher` showed a successful poll, `journalctl --user -u …` showed the startup log, `restart` and `stop` worked; the unit link and the scratch clone were removed afterwards.
- Not machine-verified: `systemctl --user enable` and `loginctl enable-linger` (the walkthrough used a transient `start` to leave no persistent service behind) and the nginx/certbot section (no public host available); those commands were reviewed against the systemd/certbot documentation instead.
