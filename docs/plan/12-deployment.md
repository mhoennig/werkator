# Step 12: Deployment and Legacy Migration

Prerequisites: steps 07, 08, 10.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Make the new GitTally deployable as a service and retire the legacy script.

## Design

Deployment (documentation plus a small generator, no self-install):

- Extend `init` (or add `init --systemd`) to generate a systemd user unit running `java -jar gittally.jar server` with `WorkingDirectory` set to the repo, `Restart=always`, and an `EnvironmentFile` for overrides — port the shape of the legacy unit, drop the self-copy/update machinery.
- Write `docs/deployment.md`: JRE requirement, jar location convention, systemd enable/start/log commands, and reverse-proxy guidance (example nginx `server` block proxying to `server.port`; TLS via the host's existing certbot — replaces the legacy managed nginx container).

Migration:

- Write `docs/migration-from-legacy.md`: mapping table legacy env vars → YAML keys (source: `00-legacy-analysis.md` and legacy `--env` output), what is intentionally not ported, and the manual steps (stop legacy service, run `init`, fill in token, install new service).
- Decide and document: no automatic import of `build-results.tsv` (history starts fresh) unless trivially cheap.

Housekeeping:

- Mark `legacy/gitTally` as deprecated in its header comment and in `README.md`.
- Review `docs/GitTally-Konzept.md` against what was actually built; update or note deviations.
- Add an ADR summarizing the architecture decisions that emerged during the rewrite (persistence choice, polling UI, no nginx management).

## Tests

- Unit test for the systemd unit generator (content assertions, no systemd interaction).
- Docs have no test, but verify every command in them by running it once.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- A fresh clone can follow `docs/deployment.md` to a running service (manual walkthrough; document the result in this file).
