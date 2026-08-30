# Step 18: Remove the legacy `branches` section

Prerequisites: none in code — v0.9.19 already made `builds` and `branches` either-or.
Read `README.md` first.
Scheduled for roughly **2026-09-05**, one week after v0.9.19 (2026-08-29), and only once the precondition below holds.

Build definitions describe a build completely since v0.9.19, and `branches` is read only while a configuration defines no build at all.
This step deletes the section, its deprecated `autoBuild` schedule, and the either-or branch in the loader, and turns a leftover `branches:` key into a named error instead of a silent loss of settings.

The `trigger` block, originally planned here, shipped earlier — see the section below for what that leaves.

## Precondition Check (run first, do not skip)

The removal adds a *rejection by name*: a configuration file that still carries a `branches:` key is refused, because a silently ignored section is exactly the failure this step exists to prevent — and the `gitTally.version` check cannot catch it, since it only bites files that declare a version, which none of the hs.hsadmin.ng configs do.

So no configuration still in play may contain the section. On vm4006:

```bash
ssh tallyman@vm4006.hostsharing.net 'cd ~/hs.hsadmin.ng && grep -n "^branches:" .git/gittally/.gittally.yml; for b in $(git for-each-ref --format="%(refname:short)" refs/remotes/origin | sed "s|origin/||"); do if git cat-file -e origin/$b:.gittally.yml 2>/dev/null && git show origin/$b:.gittally.yml | grep -qE "^branches:"; then echo "still legacy: $b"; fi; done'
```

Expected output: nothing at all.

As of 2026-08-29 this listed `master`, five `mihoe/…` branches, and the machine config;
the machine config was cleaned up on 2026-08-30, so only the committed ones are left.
The plan was: merge `mihoe/reactivate-pi-test` (the first branch with the new shape) to master, rebase the other branches onto the new master, then run this step.
Branches without a committed `.gittally.yml` are fine — they build from the machine config.

Note that `branches:` also exists as the *selector* key **inside** a build definition (`builds.<name>.branches: ["master"]`). That one stays. Only the top-level section goes. The grep above anchors at the line start for exactly that reason.

## Code

- `config/GitTallyConfig.kt`: drop the `branches` property and `AutoBuildConfig`, and `BranchConfig.autoBuild` with it.
  Rename `BranchConfig` to `BuildSettings` — with the section gone it is no longer a schema type but the resolved answer to "what does this build run", which is all it is used for.
  `buildSettings(branch, build)` then no longer needs the branch lookup: `effectiveBuildDefinitions()[build]?.applyTo(BuildSettings()) ?: BuildSettings()`.
  Keep the `branch` parameter — the callers pass it and a later per-branch concern would need it back.
- `config/ConfigLoader.kt`: delete `mergeBranchDefaults`, the legacy arm of `resolveBuildSections` (it becomes just `mergeBuildDefaults`), the `"branches"` entry in the section loop of `stripPinned`, and `LEGACY_BRANCHES_WARNING`.
  Add the rejection: a `branches` key in a file throws, scoped like the version check — the machine and project config abort the start (`checkVersion`'s call sites in `loadRaw`), a branch's committed config fails only that branch's builds (`withBranchLayer`).
  Reuse `ConfigVersionException` or add a sibling; the message must name the file and say where the settings belong now.
- `watcher/Watcher.kt`: delete `enqueueDeprecatedAutoBuilds`, its call in `enqueueDueBranches`, and the `warnedDeprecatedAutoBuild` flag.
- `config/ConfigVersion.kt`: set `FORMAT_BROKE_IN` to this release's version and `FORMAT_BROKE_DESCRIPTION` to something like "the per-branch `branches` section was replaced by build definitions".
  This is the first real use of that mechanism: a file declaring `gitTally.version.since` below this release is then refused with a message naming the change.
  Note that it only bites files that declare a version — which is why the rejection by name above exists next to it, not instead of it.
- `commands/InitCommand.kt`: nothing — the generated template has been `builds`-only with a `trigger` block since v0.9.20. Verify with `gittally init` in a scratch repo.

## Already done: the `trigger` block

`onPush`, `atTimes`, `branches`, and `activeWithin` moved into a nested `trigger` block in the release that made a definition self-contained, and writing them flat is refused since then.
Nothing is left to do for it here beyond not reintroducing the flat shape in examples.
`ConfigLoader.TRIGGER_KEYS` is already the single key the inheritance subtracts, so the removal below does not touch it.

## Tests

`branches:`-shaped YAML in tests must move to `builds:` (as of 2026-08-29):

- `config/ConfigLoaderTest.kt` — most occurrences; also delete the test "branches is honored while no build is defined and ignored as soon as one is" and replace it with one asserting the rejection.
- `build/BuildExecutorTest.kt` (the harness config and one inline config), `artifacts/BuildExecutorArtifactIntegrationTest.kt`, `artifacts/FileArtifactStoreTest.kt`, `build/DockerBuildRunnerTest.kt`, `build/DispatchingBuildRunnerTest.kt`, `server/UiControllerTest.kt`.
- `watcher/WatcherTest.kt` — the `autoBuildConfig` helper and the deprecated-schedule tests go; the `atTimes` tests stay.

Do not blind-replace: `branches: [...]` inside a build definition is the selector and must be left alone.

Add a test that a build whose definition was removed from the config still resolves to usable defaults — that path used to fall back to the branch settings.

## Documentation

- `docs/configuration.md`: delete the section "The legacy `branches` section"; drop the "only while nothing defines a build" qualifier from the branch-layer section.
- `AGENTS.md`: the invariant bullet starting "`builds` or the legacy `branches`, never both" becomes the rejection rule.
- `.claude/skills/architecture/SKILL.md`: `resolveBuildSections` no longer chooses between two sections.
- `docs/migration-from-legacy.md`: already maps to `builds.<name>`; re-check it reads correctly without the legacy section existing.

## Production

The machine config on vm4006 was already cleaned up on 2026-08-30, ahead of this step.
Its legacy `branches` block is deleted, `builds.master` is now `builds.nightly`, and `build/libs` moved into `builds.default.artifactDirs`, so the nightly job inherits the artifact directories instead of repeating them.
The file holds `git`, `server`, `builds.default`, and `builds.nightly`; `config:print --full` resolves both definitions completely, with `nightly` inheriting the docker settings and all three artifact directories.
Nothing about the section removal itself is left to do there.
The file is 600 by design; a shell redirect creates 644, so check the mode after every edit.
Backups: `.gittally.yml.20260829T085959Z.bak` (the pre-v0.9.19 shape) and `.gittally.yml.20260829T100842Z.bak` (the last one carrying the legacy block).

What remains is the shrinking: reduce the file to what is genuinely host-specific — `git`, `server`, and from `builds.default` only the pinned `docker.enabled` and `docker.network`.
Everything else — the commands, the image, the env — belongs to the repository and is duplicated there today; the machine config wins over the committed one for every branch without its own `.gittally.yml`, which is exactly the shadowing that made master build with a stale command before v0.9.19.
That move requires the repository's own config to be on master, which the precondition check above already establishes.

Decide with it where `builds.nightly` belongs.
The repository's config defines its own `release` job for master, so the nightly rebuild is the only build the host would still contribute.
Keeping it here makes the schedule host policy; moving it to the repository makes master's committed config describe all of its own builds.
Either is defensible, but it must not end up in both places: the host layer wins silently, and two definitions would run the same command twice under one Gitea status context.

The `ignoring the branches section` warning the instance logs today comes from master's committed config, not from the machine config, and it disappears with the merge rather than with this step.
After the removal that same file would be a hard error instead of a warning — which is what the precondition check guards against.

Deploy as usual (`docs/plan/15-runtime-bundle-distribution.md`), only while `/api/builds/current` is `[]`.

## Verification

- `gittally config:print --full` on vm4006 before the restart: no `branches` in the output, `builds.default` and `builds.nightly` complete, `nightly` still inheriting `docker.enabled: true`, `network: host`, and the three artifact directories.
- After the restart: no warnings about a branches section, the watcher polls without errors, and a branch build starts in `hsadmin-ng-build-env:latest`.
- Deliberately: point the running instance at a scratch repository whose config still has `branches:` and confirm the error names the file and the way out.

## Rollback

Keep the `~/opt/gittally.0.9.20.bak` that this step's deployment creates, plus a timestamped copy of the machine config.
A rollback below v0.9.19 is no longer possible from the current machine config: v0.9.18 reads its sandbox policy from the `branches` block, which is gone, and would build natively on the host.
Restoring `.gittally.yml.20260829T100842Z.bak` is the only way back to that version.
