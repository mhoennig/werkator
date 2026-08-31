> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

Artifact URLs contain the full build key (branch hash, timestamp, build hash), e.g. `/artifacts/main-81d8471cd4ea-2026-07-08T14_27_21Z-2c6fbf59869f/reports/build/doc/`.
These URLs are cryptic, and they die when the build is pruned by retention.
There is no stable URL to share in READMEs, wikis, or bookmarks that always shows the current artifacts of a branch — especially painful for `main`, which is never deleted.

## Non-Goals

- No permanent links to failed builds — permanent links are green-only by design.
- No change to the concrete `/artifacts/<artifact-key>/…` and `/builds/<artifact-key>` URLs; they keep working as before.
- No fallback from "latest green" to "latest build"; a branch that never built successfully has no permanent link (404).

## The Scenarios

### Feature: permanent artifact links for the latest green build of a branch

#### Background

- The *permanent branch key* is the sanitized branch name without the hash suffix, e.g. `feature_demo` for `feature/demo` (the full branch key with hash suffix is accepted too).
- *Green* means build status `success`.

#### Scenario#000.01: A permanent URL serves the latest green build's artifacts

So that links in READMEs, wikis, and bookmarks stay valid across new builds.

- **Given** a branch `main` with a green build that stored artifacts
- **When** `GET /branches/main/build.stdout.log` is requested
- **Then** the file from the latest green build of `main` is served
  - **and** it is served with `no-store` cache headers, because the content behind the URL changes with every new green build.

##### Verified by

- [ArtifactFileControllerTest](../../src/test/kotlin/de/hoennig/werkator/server/ArtifactFileControllerTest.kt)
- [BranchPermalinksTest](../../src/test/kotlin/de/hoennig/werkator/server/BranchPermalinksTest.kt)

#### Scenario#000.02: Directory URLs serve their index page like a static web server

So that legacy-style report links such as `/branches/main/reports/build/doc/` work.

- **Given** a stored report directory containing an `index.html`
- **When** the directory URL with trailing slash is requested
- **Then** its `index.html` is served
- **When** the directory URL without trailing slash is requested
- **Then** a redirect to the trailing-slash form is answered, so relative links inside the report resolve correctly.

##### Verified by

- [ArtifactFileControllerTest](../../src/test/kotlin/de/hoennig/werkator/server/ArtifactFileControllerTest.kt)

#### Scenario#000.03: The bare permanent URL renders a permanent artifact index

So that users can browse the latest green artifacts from one stable bookmark.

- **Given** a branch with a green build
- **When** `GET /branches/<branch-key>` is requested
- **Then** the artifact index page of the latest green build renders
  - **and** all its log and report links stay on the permanent `/branches/…` paths
  - **and** a note explains the permanent semantics and links to the concrete build page.

##### Verified by

- [UiControllerTest](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)
- [PermanentBranchRoutesTest](../../src/test/kotlin/de/hoennig/werkator/server/PermanentBranchRoutesTest.kt) (the `/branches`, `/branches/<key>`, and `/branches/<key>/<path>` routes coexist)

#### Scenario#000.04: Green-only and unambiguous resolution

So that a permanent link never points at broken artifacts or the wrong branch.

- **Given** a branch whose builds all failed
- **When** its permanent URL is requested
- **Then** 404 is answered.
- **Given** two branches whose names sanitize to the same permanent key (e.g. `feature/x` and `feature_x`)
- **When** the ambiguous permanent key is requested
- **Then** 409 is answered naming the candidates
  - **and** the full branch key with hash suffix still resolves uniquely.

##### Verified by

- [BranchPermalinksTest](../../src/test/kotlin/de/hoennig/werkator/server/BranchPermalinksTest.kt)

#### Scenario#000.05: The latest green build survives pruning

So that a permanent link stays valid while newer builds fail, as long as the branch exists.

- **Given** `artifacts.keepLatestGreen: true` (the default)
  - **and** a branch whose latest green build is older than `artifacts.retentionPerBranch` newer failed builds
- **When** the watcher prunes results and artifacts
- **Then** the latest green build and its artifacts are kept
  - **and** they are still dropped once the branch is deleted from origin.

##### Verified by

- [FileBuildResultRepositoryTest](../../src/test/kotlin/de/hoennig/werkator/build/FileBuildResultRepositoryTest.kt)
- [WatcherTest](../../src/test/kotlin/de/hoennig/werkator/watcher/WatcherTest.kt)

#### Scenario#000.06: The branches view links the permanent URL

So that users can discover the permanent link without constructing it by hand.

- **Given** the branches view (`/branches` and `GET /api/branches`)
- **When** a branch has a green build
- **Then** the API entry carries `latestGreenUrl`
  - **and** the Artifacts column shows a 🔗 link to it, next to the 📄 link of the concrete latest build.

##### Verified by

- [BranchListingTest](../../src/test/kotlin/de/hoennig/werkator/server/BranchListingTest.kt)
- [UiControllerTest](../../src/test/kotlin/de/hoennig/werkator/server/UiControllerTest.kt)

## The Solution

Permanent URLs are resolved dynamically per request instead of via filesystem symlinks, because `BuildResultRepository` already knows the latest green build authoritatively and symlinks would fight the `NOFOLLOW_LINKS` guards in serving and pruning.

- `ArtifactKeys.permanentBranchKey` is the hash-free sanitized branch name; `BranchPermalinks` resolves it (or the full branch key) to the branch's latest green build, rejecting ambiguous keys with 409.
- `ArtifactFileController` gained `GET /branches/{branchKey}/{*path}`: it serves the resolved build's files with the existing traversal guards, adds directory→`index.html` handling with a trailing-slash redirect, and answers everything `no-store`.
- `UiController` gained `GET /branches/{branchKey}`, reusing the artifact-index view with a parameterized link base (`filesBase`), so the permanent index page only emits permanent links.
- `BuildResultRepository.latestGreenFor` and a `keepLatestGreen` prune flag protect the link target: the newest SUCCESS entry per branch survives retention pruning (config `artifacts.keepLatestGreen`, default `true`), and the artifact store keeps its directory because it prunes by the surviving results.
- `GET /api/branches` entries carry `latestGreenUrl`; the branches view renders it as a 🔗 icon in the Artifacts column (server-rendered Thymeleaf and `gittally.js` alike).

## Open Questions

- `artifacts.keepLatestGreen` defaults to `true`; set it to `false` to restore strict retention (the permanent URL then 404s once the green build is pruned).
- Redirecting the bare `/branches/<key>/` (trailing slash) to the index page keeps the file route and the UI route disjoint; a direct render was not worth duplicating the page mapping.

## Additional Changes

- The concrete artifact index page (`/builds/<artifact-key>`) now builds its file links from the shared `filesBase` model attribute; rendered output is unchanged.

## Follow-up PRs

- Optionally mention the permanent URL in the Gitea commit status or README badge documentation.
