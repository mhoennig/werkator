# Step 21: Werkdock Extraction and the Managed-Webspace Install Path

Prerequisites: step 17 (merged to `main` as PR #4, commit `71f1fc6`).
Read `README.md` first.
This step is a roadmap: it records where the bwrap work drifted from the original intent, and breaks the correction into sessions (A–D below).
Each session is sized like a normal step.
Werkdock is developed in the `werkdock/` subdirectory of this repository first and moves to its own repository later; the whole effort runs on the branch `werkdock-extraction`.

## What Was Planned, and What the Branch Built Instead

Two intents from the existing documentation ended up competing in the bwrap work merged as PR #4:

1. **ADR 0006 / step 15**: Werkator is *built locally* and distributed as a self-contained runtime bundle; the target host only unpacks and runs it.
   That install path exists and is documented — but only for Hostsharing **container servers** (`docs/deployment.md`, "Hosts Without a Java Runtime", verified on vm4006).
2. **Step 17**: bubblewrap as the third build runtime, with the stated target use case "Werkator builds Werkator itself on a Managed Webspace".

Step 17 itself proved the self-build unnecessary for deployment: the precondition section records that the runtime bundle runs on the webspace unchanged (glibc floor `GLIBC_2.15`, checked on h68), "so no container build and no second build machine are needed for this platform".
The merged code nevertheless implements the self-build end to end — `tools/remote install` clones the repository onto the webspace and `tools/remote build` builds Werkator there inside the bwrap sandbox.
That is a working prototype and a good proof of the sandbox, but as a *deployment* path it inverts intent 1: the webspace should receive a locally built bundle, exactly like vm4006 does.

Independently, the bwrap machinery itself (rootfs archive build, prerequisites check, the mount/uid-mapping invocation in `BwrapBuildRunner`) is generic filesystem isolation, not Werkator-specific.
The plan is to extract it as a small docker-like tool: filesystem isolation only, everything else (network, uid, `/proc`, `/dev`) shared with the host — usable on Managed Webspaces to install one's own program versions, with Werkator as its first consumer.
It grows in the `werkdock/` subdirectory of this repository and moves to its own repository once it stands on its own.

## Naming the Extracted Tool

**Werkdock** — decided 2026-09-01.
A dock is the enclosed basin in which ships are built, so the name carries both halves of the tool at once: the closed-off area (filesystem isolation) and the docker-light ambition.
The metaphor extends to the contract: the dock gate controls what passes, while the water outside is shared with the whole harbor — network, uid, `/proc`, `/dev` from the host.
The audible nearness to Docker is read as an honest genre label, not as an accident.
"Dock" is the same word in German and English — the only candidate that needed no translation in either direction.
As of 2026-09-01 there is no GitHub repository, product, or company of that name.

Considered and dropped, over three naming rounds:

- *Werkwrap* (the working title): names the mechanism — a wrapper over `bwrap` — rather than the result; one abandoned zero-star GitHub repo of that name also exists.
- *Werkroot*: technically the most precise (the isolated artifact is a root filesystem; lineage chroot → fakeroot), completely free — the runner-up.
- *Werkgrund*: "own ground to build on", free; but "Grund" also reads as "reason" and signals neither isolation nor containers.
- German root-words: *Wurzelwerk* (the finest word, but a well-known German gardening brand, and it inverts the Werk-family order), *Werkwurzel* (family-true but botanical), *Stammwerk*, *Wurzelraum*.
- Enclosed-area words: *Werkkammer* (sober engineering chamber), *Werkinsel* (isolation literally from *insula*), *Werkgehege* (best tagline — „damit sich Programmversionen nicht ins Gehege kommen" — but zoo overtones), *Werkklause*, *Werkzone*, *Werkhof* (the Swiss municipal works yard), *Werkgarten* (walled-garden connotation).
- *Werkbank* (taken on GitHub at least twice, and a common German word), *Werkbox* (crowded `*box` sandbox namespace), *Kapsel* (SAP's Kapsel framework).

## The Sessions

### A — Close step 17's open ends (this repo)

The `BuildRunner` half is a keeper regardless of the extraction; it is merged (PR #4), but its paperwork is not finished.

- Rename `docs/prs/2026-08-31-PR#000-bwrap-build-runtime.md` and its scenario IDs to the real number, #4.
- Mark `tools/remote install`/`build` in the script header as a prototype of the self-build workflow, superseded by session D.
- Write the bwrap-runtime ADR — step 17 says "ADR 0007", but 0007 is taken by build definitions since 2026-08-28; the ADR becomes **0008**.
- Update the architecture skill: it does not mention the third runtime yet.

### B — Bootstrap Werkdock (subdirectory `werkdock/`, later its own repo)

A docker-like CLI over `bwrap`, filesystem isolation only.

- Semantics: an *image* is a rootfs archive; an *instance* is an unpacked, writable directory tree and corresponds to a docker container; `werkdock run [flags] IMAGE [CMD...]` executes in the sandbox with uid 0 mapped to the calling user.
- The surface is docker-compatible as far as the filesystem-only contract allows — verbs, flags, and (deferred) a Docker-Engine-API daemon for Testcontainers; levels and limits in Werkdock RFC 0002.
- Decided 2026-09-01: RFC 0002 levels 2 and 3 are deferred indefinitely; the immediate goal of this session is the minimal build-capable CLI — `doctor`, `load`, `run` — sufficient for the sandbox builds of Werkator, Werkbaum (Kotlin/Gradle backend plus Node frontend), and Werkdock itself (Go); while Werkdock lives in this repository, its own CI is just a build definition in this repository's `.werkator.yml`.
- Host-shared by design, not by omission: network, uid mapping, `/proc`, `/dev`, `/tmp` come from the host; document this as the contract, since it is what makes the tool work without root on a Managed Webspace.
- Moves in from Werkator: `tools/build-bwrap-rootfs.sh` (becomes the image build), the generic half of `tools/werkator-build-prerequisites.sh` (becomes `werkdock doctor`: userns capability, quota headroom), and the invocation logic of `BwrapBuildRunner` (mount ordering, mountpoint pre-creation, uid mapping — the parts hardened on the real webspace; the squash commit `71f1fc6` preserves the individual fix messages).
- Known floor: bubblewrap 0.8.0 on the webspaces has no `--overlay`; writable spots are tmpfs/bind mounts until the platform reaches 0.9.
- Own docs, plan, and ADRs under `werkdock/` from the start, so the later repository split is a directory move; the Werkator side only keeps what is Werkator-specific (the git-metadata mounts of step 16 and the config pinning).
- Keep `werkdock/` self-contained: no imports from Werkator code, no Gradle coupling to the Werkator build — it must build and test on its own.

### C — Werkator consumes Werkdock (this repo, after B; implemented 2026-09-01 on branch `werkator-consumes-werkdock`)

- `BwrapBuildRunner` shells out to `werkdock run` instead of assembling the raw `bwrap` argv — same pattern as git and docker: CLI, no library.
- Config keys (`bwrap.enabled`, `bwrap.rootfs`) and their pinning stay as they are; only the executor behind them changes.
  One key was added: `bwrap.werkdock` (the executing binary, default via PATH) — pinned like the rest of the sandbox policy, since a branch must not substitute the executing binary.
- Decided: the git-metadata mounts stay Werkator-side, passed as `-v …:ro` / `--tmpfs` / `-v` options whose flag order werkdock preserves (it grew `--tmpfs` and an ordered mount list for exactly this); the secrets-masking of `.git/werkator/` holds unchanged.
- Decided: the rootfs archive becomes a werkdock *image* (`werkator-buildenv-<source-hash>`, checked via `werkdock images`, loaded via `werkdock load`) in werkdock's own store — shared across every repository of the OS user, which resolves step 22's buildenv-sharing question; only the URL download cache and the persistent `/root` toolchain home stay under `.git/werkator/buildenv/`.
- Consequence of werkdock's `--clearenv`: the server environment no longer leaks into builds, and the runner's TMPDIR workaround is gone.

### D — The Managed-Webspace install path (this repo, independent of B/C; implemented 2026-09-01 on branch `werkator-consumes-werkdock`)

Bring intent 1 to the webspace: build locally, install the bundle — Werkator never builds itself on the target.

- `tools/remote install` loses the repository clone and the GitHub-key step; it uploads the locally built runtime bundle (built on demand, as today) and runs `init`.
- The rootfs upload stays, but for its real purpose: the sandbox for the repositories this instance *watches*, not for building Werkator.
- `tools/remote build` (the self-build) is retired with session A's prototype marker.
- Untangle the two roles `tools/remote` mixes (noted 2026-09-01): some of its commands manage the *builder* (the installed Werkator instance: install, start, control-token, the runtime bundle, the rootfs it builds others in), others act on the *built* (the watched repository: build, branch selection) — and Werkator itself overlaps with both (the instance IS a Werkator, and status/build/retry exist as `werkator` CLI commands too).
  On the self-building instance both roles coincide in one product, which misleads: "updating werkator" can mean swapping the builder's bundle or building the repo's head, and they are different operations with different risks.
  Session D's replacement must name the role in every command and in the script's vocabulary (e.g. `instance install`/`instance update` vs `repo build`), and prefer delegating built-side operations to the `werkator` CLI instead of reimplementing them.
- `docs/deployment.md` gains "Hostsharing Managed Webspace" as the third deployment variant — step 17 required this to be written from a verified setup, and the branch's live run provides exactly that.

## Session Notes

- 2026-09-01: The fat build image exists and is live on mih34: `tools/build-bwrap-rootfs.sh` gained `--pkgs-extra`, the archive `werkator-buildenv-trixie-java-go-node.tar.zst` (515 MB, JDK 21 + Go + Node/npm) was built locally, uploaded checksum-verified, and the machine config switched to it (deduplicating nine identical bwrap blocks the install prototype had appended).
  The old archive and its unpacked environment stay as rollback until the `werkdock` build pool is green.
- 2026-09-01, later: sessions A and B are done and live-verified on mih34 — the skeleton (`doctor`, `load`, `run` over the bwrap engine) builds itself there as pool `<branch>@werkdock`, and the CI-built static binary runs.
  Three defects found and fixed on the way: unanchored tar excludes dropped the Go stdlib's `sys` directory from the archive, pam_tmpdir's `TMPDIR` leaked into the sandbox (Werkator-side fix; Werkdock is immune via `--clearenv`), and non-report artifacts were stored below `reports/` and invisible in the UI.
  The image was then trimmed (headless JDK, en/de locales only, no man/doc/apt-lists): 351 MB compressed — smaller than the original JDK-only archive despite carrying Go and Node.
  All rollback assets on mih34 are removed; the PR for this branch is prepared (PR-doc with `PR#000` placeholder) and will be opened later.
- 2026-09-01, session C deployed to mih34: the werkdock binary sits at `.werkator/bin/werkdock`, the machine config names it in `bwrap.werkdock`, the runtime bundle carries the delegating runner, and the TMPDIR workaround left the machine config (obsolete under werkdock's clearenv).
- 2026-09-01, session D done and live-verified on mih34: `tools/remote` reworked to role-named commands (`instance-install`/`instance-update`/`instance-start` for the builder, `repo-init` for the built; the retired `install`/`build`/`start` fail loudly naming their successors); the self-build, the repo clone for it, and the GitHub-key step are gone — the instance installs from locally built artifacts (bundle + werkdock), the watched repo clones anonymously via https.
  `instance-update` refuses to swap under a running build, `repo-init` is idempotent (checksum-skipped rootfs upload; the machine-config guard whose indentation mismatch once appended nine duplicate bwrap blocks is fixed); `docs/deployment.md` gained the Managed Webspace as the third variant, written from the verified setup.

## Acceptance Criteria

- Session A: PR-doc renamed to #4, ADR 0008 written, architecture skill mentions the third runtime, `tools/remote` header carries the prototype note.
- Session B: the `werkdock/` subdirectory holds a self-contained tool in which `werkdock doctor`, an image build, and `werkdock run` work on a Managed Webspace without any Werkator involvement.
- Session C: `./gradlew build` green with `BwrapBuildRunner` delegating to `werkdock`; the pinned-key tests and the metadata-masking tests unchanged and green.
- Session D: a fresh Managed Webspace reaches a running, HTTPS-reachable Werkator via `tools/remote werkator install` + `start` without ever compiling on the target; `docs/deployment.md` documents it.
