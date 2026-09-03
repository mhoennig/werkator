> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## Related Links

- The live system page: <https://werkator.javagil.de/system> (instance `mih09-werkator` on `mih09.hostsharing.net`, a Hostsharing Managed Webspace).
- [Step 09: System Metrics](../plan/09-system-metrics.md) — where the disk metric comes from.
- [ADR 0008](../adrs/0008-2026-09-01.bwrap-build-runtime.md) and [`werkdock doctor`](../../werkdock/internal/doctor/doctor.go) — the one place that already reads the group quota, as a one-off check before the first build.
- Hostsharing-internal: the package's quota is set in HSAdmin; the values below were read on the host with `quota(1)`.

## The Problem

The system page shows the disk of the *host*, not the disk the instance can use.
On `mih09` it reads `Disk total: 70.99 GiB`, `Disk used 37.13 GiB`, `Disk free 33.86 GiB` — the numbers of the whole `/` volume, shared by every package on that machine.
What actually limits Werkator there is a **group quota** of the package `mih09`: 8 GiB soft limit, 12 GiB hard limit, 1.04 GiB used, measured on 2026-09-03 (see [Attachments](#measured-on-mih09-2026-09-03)).
So the page promises 33 GiB of headroom where 7 GiB exist, and its warn/critical highlighting (80 %/90 % of the total) can never fire before a build fails with "Disk quota exceeded".

The disk metric is read via `java.nio.file.FileStore` with `df` semantics (step 09).
That is right on a host Werkator owns (vm4006, a Docker host) and wrong on every host where a user or group quota is the binding limit — the Hostsharing Managed Webspace being the deployment variant we now run in production.
`werkdock doctor` already knows this: it checks the group-quota headroom before the first build, because the free space of the volume said nothing (a 1 GiB quota blocked a build mid-flight on `h68`, step 17).
The system page should show the same truth, continuously.

## Non-Goals

- Implementing the change — this PR is the plan only; the implementation follows in the next PR.
- Inode (file-count) quotas: `quota(1)` reports them, but the Gradle caches on `mih09` use 14 226 of 16.7 M files; a follow-up if it ever matters.
- Alerting or refusing to start a build on a full quota — the page only shows; `werkdock doctor` keeps the one-off pre-build check.
- A configuration switch: the quota is detected, never declared (see Open Questions).
- Changing the JSON field names of `GET /api/system` or the metric rows of the page: `diskTotalGib`, `diskUsedGib`, `diskFreeGib` keep their names and their meaning "the budget the instance can fill".

## The Scenarios

### Feature: the disk metric is the tightest budget — user quota, group quota, or the volume

#### Background

- `quota(1)` prints one block per subject (`-u` the user, `-g` its groups), each with one line per filesystem: `blocks` (1 KiB units, currently used), `quota` (soft limit), `limit` (hard limit), grace, then the same four for files.
  A subject without quota prints `… : none`.
  A `*` after `blocks` marks "over the soft limit".
- Three **candidates** can limit what a directory may still take: the user quota, the group quota (each the lines of the directory's filesystem) and the volume itself (the file store, `df` semantics).
  Each has a headroom: `soft − blocks` for a quota, the usable space for the volume.
- The **binding candidate** is the one with the smallest headroom; its numbers are the disk metric — total, used and free come from one source, never mixed.
  On `mih09` today that is the group quota (6.96 GiB left against 33.86 GiB on the volume); a user quota, once the webspace introduces one, joins the comparison without any change.
- For a quota the **total** is its **soft** limit, the hard limit is shown alongside (see Open Questions for the choice).
- Where no quota line matches the directory's filesystem, the volume is the only candidate — Docker hosts and developer machines render exactly as today.
- The directory is the first served repository's, as for the file-store metric today.

#### Scenario#13.01: A group quota replaces the volume numbers

So that the operator of a Managed Webspace sees the budget the package can fill, not the size of the host's disk.

- **Given** `quota -u -g` reports no user quota and a group quota on the repository's filesystem with 8 GiB soft limit, 12 GiB hard limit and 1.04 GiB used
  - **and** the file store reports 71 GiB total and 34 GiB free
- **When** a sample is taken
- **Then** `diskTotalGib` is 8.00, `diskUsedGib` 1.04 and `diskFreeGib` 6.96
  - **and** the snapshot names the quota: group `mih09`, its filesystem, and the hard limit 12.00 GiB.

##### Verified by

- [SystemMetricsCollectorTest — "a group quota on the repository's filesystem replaces the file-store disk numbers"](../../src/test/kotlin/de/hoennig/werkator/metrics/SystemMetricsCollectorTest.kt) (planned)
- [DiskQuotaTest — "the mih09 output parses into one group line per filesystem and no user line"](../../src/test/kotlin/de/hoennig/werkator/metrics/DiskQuotaTest.kt) (planned, fixture: the attachment below)

#### Scenario#13.02: The tightest of user quota, group quota and volume binds

So that neither a user quota below the group's, nor a nearly full host volume below both, is hidden by the wider budgets.

- **Given** a user quota with 2 GiB headroom and a group quota with 7 GiB headroom on the same filesystem
  - **and** the volume has 34 GiB usable
- **When** a sample is taken
- **Then** the user quota binds: its soft limit is the total, its blocks the used value
  - **and** with the user quota reporting `none`, the group quota binds
  - **and** with the volume down to 1 GiB usable, the volume binds and the metric shows the file-store numbers, quotas or not.

##### Verified by

- [DiskQuotaTest — "among user quota, group quota and volume the smallest headroom binds"](../../src/test/kotlin/de/hoennig/werkator/metrics/DiskQuotaTest.kt) (planned)

#### Scenario#13.03: Only the quota of the repository's filesystem counts

So that a full quota on another volume (on `mih09`: `/dev/sdb1`) does not shrink the metric of the volume Werkator writes to.

- **Given** quota lines for two filesystems
  - **and** the repository directory's file store is named like the first one
- **When** the binding quota is chosen
- **Then** the second filesystem's lines are ignored
  - **and** the name is matched exactly, or by its last path segment when the file store reports a resolved device path (`/dev/sdb1` vs `/dev/disk/by-id/…`).

##### Verified by

- [DiskQuotaTest — "only the lines of the directory's file store are considered, matched exactly or by device name"](../../src/test/kotlin/de/hoennig/werkator/metrics/DiskQuotaTest.kt) (planned)

#### Scenario#13.04: Without a quota the volume stays the source

So that hosts without quota tooling, without a quota, or with an unreadable `quota` output render exactly as before.

- **Given** `quota` is absent, fails, prints `none` for user and group, or prints only other filesystems
- **When** a sample is taken
- **Then** `diskTotalGib`, `diskUsedGib` and `diskFreeGib` are the file-store values
  - **and** the snapshot names the volume as the source and no quota
  - **and** an absent or failing `quota` is logged once, not every 60 s, like every other source.

##### Verified by

- [SystemMetricsCollectorTest — "without a quota the volume stays the disk source"](../../src/test/kotlin/de/hoennig/werkator/metrics/SystemMetricsCollectorTest.kt) (planned)
- [SystemMetricsCollectorTest — "unreadable sources degrade to null metrics, never fail the sample"](../../src/test/kotlin/de/hoennig/werkator/metrics/SystemMetricsCollectorTest.kt) (existing, extended by the quota source)

#### Scenario#13.05: A changed disk source restarts the disk series

So that the min/max/avg of a 1 GiB quota metric are not poisoned by the 37 GiB volume history of the previous binary — the max would otherwise read 37.13 GiB forever.

- **Given** a persisted aggregation state whose disk series were recorded from the file store
- **When** the first sample after the update finds a binding quota
- **Then** the `diskUsedGib` and `diskFreeGib` series start afresh
  - **and** every other series continues
  - **and** the state records the disk source, so the next restart continues the quota series.

##### Verified by

- [SystemMetricsCollectorTest — "a changed disk source restarts the disk series and keeps the others"](../../src/test/kotlin/de/hoennig/werkator/metrics/SystemMetricsCollectorTest.kt) (planned)

#### Scenario#13.06: The page says which budget it shows

So that `Disk total: 8.00 GiB` on a 71 GiB host is not mistaken for a broken metric.

- **Given** a snapshot with a binding group quota
- **When** the system page renders or polls
- **Then** the info line reads `Disk total: 8.00 GiB (group quota mih09, hard limit 12.00 GiB)`
  - **and** with a binding user quota `Disk total: 4.00 GiB (user quota mih09-werkator, hard limit 6.00 GiB)`
  - **and** with the volume binding although quotas exist `Disk total: 70.99 GiB (volume, tighter than the quotas)`
  - **and** without any quota it reads `Disk total: 70.99 GiB` as today
  - **and** the server-rendered line and the polled line are identical.

##### Verified by

- [UiViewsTest — "the disk total names the binding source: user quota, group quota, or the volume"](../../src/test/kotlin/de/hoennig/werkator/server/UiViewsTest.kt) (planned)
- `werkator.js` mirrors `UiFormats.diskTotal` (manual: the polled line must equal the rendered one after the first refresh)

#### Scenario#13.07: The highlighting follows the quota

So that the warn/critical colours fire before a build hits "Disk quota exceeded".

- **Given** a binding quota with 8 GiB soft limit and 6.6 GiB used
- **When** the system page renders
- **Then** the `Disk used` cell is highlighted `metric-warn` (82 %), and `metric-crit` from 7.2 GiB.

##### Verified by

- [UiViewsTest — "utilization highlights warn from 80% and crit from 90% of the total"](../../src/test/kotlin/de/hoennig/werkator/server/UiViewsTest.kt) (existing — the total is now the quota, nothing else changes)

## The Solution

This PR records the plan; the code lands in the next PR.

**Read the quota through the CLI, like git and Docker.**
Linux exposes quotas only through the `quotactl` syscall, which Java cannot reach without JNI/JNA — a new runtime dependency and a native layer for one number.
`quota(1)` is installed wherever quotas are set (Debian's `quota` package on the Managed Webspaces, version 4.06 on `mih09`), so the collector shells out: `quota -u -g --no-wrap --raw-grace` (`-ugwp`).
`--no-wrap` keeps long device names such as `/dev/disk/by-id/wwn-…-part2` on one line, `--raw-grace` prints the grace columns as numbers instead of leaving them empty, so every filesystem line has the same nine fields and the parser needs no column heuristics.
The exit status is not read as a failure signal — `quota` also uses it to say "over quota" — only the parsed output counts; an absent binary or an empty output means "no quota", never a failed sample.
This is the same decision `werkdock doctor` took in Go; the two parsers stay separate because the tools are different binaries in different languages, but the fixture is the same real output.

**Choose the binding candidate in a pure function.**
`DiskQuota` (new, package `de.hoennig.werkator.metrics`) parses the output into lines `{kind user|group, subject, filesystem, blocksKib, softKib, hardKib}` and keeps the lines of the directory's file store (`Files.getFileStore(dir).name()` is the mount's device string, the same string `quota` prints; a resolved device path is matched by its last segment as `werkdock` does).
Each remaining line becomes a candidate `DiskSpace` with `total = soft`, `used = blocks`, `free = max(0, total − blocks)`; a line whose soft limit is 0 (unset) uses the hard limit as total, a line with both 0 is no candidate.
The volume's `fileStoreDiskSpace(dir)` is the last candidate, and `bindingDiskSpace(candidates)` returns the one with the smallest `free` — the tightest budget wins, and total, used and free always come from that one source.
All of it is pure over strings and numbers, so the whole matrix — user only, group only, both, none, volume tighter than the quotas, two filesystems, `*` marker, `none` line — is a Kotest table.

**The collector gets one more injectable source.**
`SystemMetricsCollector` gains `quotaOutput: () -> String?` next to `diskSpace` (the process call with a 5 s timeout in production, a string in tests); `readDisk()` collects the quota candidates plus the file store and takes the binding one — a failing `quota` simply leaves the volume as the only candidate.
`DiskSpace` gains a `source: DiskSource` (`kind` `volume|user|group`, `subject`, `filesystem`, and for a quota `softLimitGib`/`hardLimitGib`), carried into `SystemMetrics.diskSource` — an additive JSON field, the three existing disk fields keep their names; `quotasPresent: Boolean` says whether a quota lost against the volume, for the info line.
The persisted state gains `diskSource` (`"volume"` or `"quota:<kind>:<subject>:<filesystem>"`); a mismatch drops the two disk series before the sample is recorded (Scenario#13.05).
That reset also fires when the binding candidate switches at runtime, e.g. from the group quota to a newly introduced user quota — the series then describe one budget at a time.
The quota is read every sample: it is one syscall behind a small process, cheaper than the repo-size walk, and a raised quota should show within a minute.

**The page names the budget.**
`UiFormats.diskTotal(metrics)` formats `8.00 GiB (group quota mih09, hard limit 12.00 GiB)`, `… (user quota …)`, `70.99 GiB (volume, tighter than the quotas)` or the plain total when no quota exists; `werkator.js` gets the identical function for the poll — the UI invariant that server-rendered and polled output match.
Rows, labels and the highlighting stay as they are: `utilizationClass(used, total)` simply receives the quota as the total.

**Where it is verified live.**
After the deployment on `mih09` the page must read `Disk total: 8.00 GiB (group quota mih09, hard limit 12.00 GiB)`, `Disk used` about 1.04 GiB and `Disk free` about 6.96 GiB, with the `Repo size` row unchanged at about 0.73 GiB — the used value is the whole package's usage (every user of group `mih09`), which is what counts against the budget, while the repo size stays Werkator's own share.
On `vm4006` (Docker host, no quota) the page must render exactly as before.
The first sample after the update restarts the disk min/max/avg, visible as `Max` dropping from 37.13 GiB to the current value.

**Order of work for the implementing PR:**

1. `DiskQuota` parser and selection with the table test and the `mih09` fixture.
2. `SystemMetricsCollector`: the quota source, the fallback, `diskSource` in the state, the series reset.
3. `SystemMetrics.diskSource`, `UiFormats.diskTotal`, `SystemMetricsView`, `werkator.js`, `UiViewsTest`.
4. Docs: the metrics paragraph of the architecture skill, one sentence in `docs/deployment.md` (Hostsharing section) and in `docs/plan/09-system-metrics.md` (implementation note), and this PR-doc's "Verified by" links turned from planned into real.
5. Deploy to `mih09` via `tools/remote --env-file .env.mih09 werkator instance-update`, check the page and the journal for the one-time source log line.

## Open Questions

- **Soft or hard limit as the total?** Planned: the soft limit, with the hard limit in the info line.
  Beyond the soft limit the grace period starts and writes fail once it expires, so for a service that runs for weeks the soft limit is the effective one; and a page that turns critical *before* the hard stop is the point of the highlighting.
  The reviewer may prefer the hard limit as the total and the soft limit as the warn threshold instead — that would need a third highlighting rule, hence not planned.
- **The volume as a candidate — decided.** The owner asked for the smallest of user quota, group quota and free disk space, so the volume competes on equal terms instead of being only the fallback; that is what Scenario#13.02 and `bindingDiskSpace` describe.
- **A configuration switch?** Planned: none.
  The quota is detected and the volume is always a candidate; a key `metrics.disk: quota|filestore` would be a fourth place to keep in sync (`WerkatorConfig`, `init` templates, `docs/configuration.md`) for a choice nobody is expected to make.
- **Which directory's filesystem?** Planned: the first served repository's, as today.
  The artifact root and the worktrees live under it by default; an artifact root on another volume would need its own line — noted as a follow-up.

## Prerequisite PRs

- None; the change is confined to the metrics package and the system page.
  It applies equally on top of PR #12 (the registry), which only changed the directory the disk metric is read for from `workingDir` to `repoDirs().first()`.

## Follow-up PRs

- The implementation of this plan.
- Inode quotas, if a host ever runs into the file limit before the block limit.
- A per-volume disk metric when the artifact root is configured onto another file store than the repository.

## Attachments

### Measured on mih09 (2026-09-03)

Read as `mih09-werkator` (uid 120974, gid 102180 `mih09`); this output is the parser fixture.

```
$ quota -u -g -w -p
Disk quotas for user mih09-werkator (uid 120974): none
Disk quotas for group mih09 (gid 102180):
     Filesystem  blocks   quota   limit   grace   files   quota   limit   grace
/dev/disk/by-id/wwn-0x0000000000000001-part2 1088116  8388608 12582912       0   14226  16777216 25165824       0
      /dev/sdb1 1456376  10485760 15728640       0   52983  20971520 31457280       0

$ df -Pk "$HOME"
Filesystem                                   1024-blocks     Used Available Capacity Mounted on
/dev/disk/by-id/wwn-0x0000000000000001-part2    74436100 38933504  35502596      53% /

$ du -sk "$HOME"
954544  /home/pacs/mih09/users/werkator
```

In GiB: the group's soft limit is 8.00, the hard limit 12.00, the usage 1.04 (of which Werkator's home is 0.91); the volume is 70.99 with 33.86 free — the numbers the page shows today.
Without `--raw-grace` the empty grace columns vanish and the lines have seven fields instead of nine; without `--no-wrap` the long device name is printed on a line of its own.
