# Step 18: Remove the legacy `branches` section, group the trigger

Prerequisites: none in code — v0.9.19 already made `builds` and `branches` either-or.
Read `README.md` first.
Scheduled for roughly **2026-09-05**, one week after v0.9.19 (2026-08-29), and only once the precondition below holds.

Build definitions describe a build completely since v0.9.19, and `branches` is read only while a configuration defines no build at all.
This step deletes the section, its deprecated `autoBuild` schedule, and the either-or branch in the loader, and turns a leftover `branches:` key into a named error instead of a silent loss of settings.

It carries a second, unrelated break in the same release, deliberately: the trigger and selector keys of a definition move into a `trigger` block.
Both changes force the same repositories to migrate the same files, so they cost one migration, one `FORMAT_BROKE_IN`, and one deploy together — and two of each apart.

## Precondition Check (run first, do not skip)

The removal adds a *rejection by name*: a configuration file that still carries a `branches:` key is refused, because a silently ignored section is exactly the failure this step exists to prevent — and the `gitTally.version` check cannot catch it, since it only bites files that declare a version, which none of the hs.hsadmin.ng configs do.

So no configuration still in play may contain the section. On vm4006:

```bash
ssh tallyman@vm4006.hostsharing.net 'cd ~/hs.hsadmin.ng && grep -n "^branches:" .git/gittally/.gittally.yml; for b in $(git for-each-ref --format="%(refname:short)" refs/remotes/origin | sed "s|origin/||"); do if git cat-file -e origin/$b:.gittally.yml 2>/dev/null && git show origin/$b:.gittally.yml | grep -qE "^branches:"; then echo "still legacy: $b"; fi; done'
```

Expected output: nothing at all.

The same holds for the second change: no configuration may still carry a definition with a flat `onPush`, `atTimes`, `branches`, or `activeWithin`. Since the two migrations touch the same files, do them in one commit per repository.

As of 2026-08-29 this listed `master` and five `mihoe/…` branches, plus the machine config.
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
- `commands/InitCommand.kt`: the generated template is `builds`-only since v0.9.19, but its `default` entry and the commented example job need the `trigger` block. Verify with `gittally init` in a scratch repo.

## The `trigger` block

`onPush`, `atTimes`, `branches`, and `activeWithin` move from the definition into a nested `trigger`:

```yaml
builds:
  default:
    trigger:
      onPush: true
      branches: ["*", "!master"]
    buildCommand: ./gradlew check
```

The point is that the inheritance rule becomes structural instead of a remembered list: everything except `trigger` is inherited from `builds.default`.
Today `ConfigLoader.SELECTOR_KEYS` enumerates the four keys, and whoever adds a fifth selector without touching that list makes it silently inheritable — a job firing on branches that are none of its business, noticed only much later.
After the change `mergeBuildDefaults` subtracts the single key `trigger`, and a new selector is automatically right.

- `config/BuildDefinition.kt`: a nested `TriggerConfig(onPush, atTimes, branches, activeWithin)`; `selects`/`selectsByName`/`maxAge` read from it.
- `config/ConfigLoader.kt`: `SELECTOR_KEYS` becomes the single key `trigger`.
- Reject a definition that still carries any of the four keys flat, by name and with the same scoping as the `branches` rejection. `FORMAT_BROKE_IN` does not cover this: the hs.hsadmin.ng configs declare no version, and a flat `onPush` nobody reads any more means the branch stops building, wordlessly.
- `branches` inside `trigger` is the selector and unrelated to the removed top-level section — the two named the same thing, which is part of why the block is clearer.

`trigger` also groups `branches`/`activeWithin`, which are selectors rather than triggers in the strict sense. That is the established shape (GitHub Actions writes `on: push: branches: [...]`) and was chosen over `when`.

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
- `docs/migration-from-legacy.md`: already maps to `builds.<name>`; re-check it reads correctly without the legacy section existing, and move the trigger keys of the mapping table into `trigger`.
- Every YAML example in `docs/configuration.md`, `docs/deployment.md`, and the ADRs that shows a build definition needs the `trigger` block.

## Production

The machine config on vm4006 was rewritten for v0.9.19 and still carries its legacy block as the rollback path to v0.9.18 (backup: `.git/gittally/.gittally.yml.20260829T085959Z.bak`).
Delete the block — everything below the comment marking it as legacy — before deploying this release, with a fresh timestamped backup.
The file keeps `git`, `server`, `builds.default`, and `builds.master`.
It is 600 by design; a shell redirect creates 644, so check the mode afterwards.

While rewriting it, shrink it to what is genuinely host-specific: `git`, `server`, and from `builds.default` only the pinned `docker.enabled` and `docker.network`.
Everything else — the commands, the image, the env — belongs to the repository and is duplicated there today; the machine config wins over the committed one for every branch without its own `.gittally.yml`, which is exactly the shadowing that made master build with a stale command before v0.9.19.
That move requires the repository's own config to be on master by then, which the precondition check above already establishes.

Deploy as usual (`docs/plan/15-runtime-bundle-distribution.md`), only while `/api/builds/current` is `[]`.

## Verification

- `gittally config:print --full` on vm4006 before the restart: no `branches` in the output, `builds.default` and `builds.master` complete, `master` still inheriting `docker.enabled: true` and `network: host`.
- After the restart: no warnings about a branches section, the watcher polls without errors, and a branch build starts in `hsadmin-ng-build-env:latest`.
- Deliberately: point the running instance at a scratch repository whose config still has `branches:`, and at one with a flat `onPush:`, and confirm both errors name the file and the way out.

## Rollback

Keep `~/opt/gittally.<previous>.bak` and the config backup.
A rollback needs both, because the previous version reads the machine config's `branches` block for its sandbox policy and this step deletes it.
