# Step 22: One Werkator Instance, Many Repositories

Prerequisites: none in code; step 21's Werkdock work is independent.
Read `README.md` first.
This step is a roadmap in sessions (A–E), like step 21; each session is sized for one focused Claude Code session.

## The Problem

"One instance per repository" is a founding tenet (`docs/Werkator-Konzept.md`, AGENTS.md) — and on a Managed Webspace it does not scale even to two repositories.
Building Werkbaum next to Werkator on mih34 today means: a second pac user or a second service, a second assigned port, a second tunnel or domain, a second UI, a second metrics page.
Every repository added multiplies operations, while the instance-level resources (port, UI, watcher schedule, executor slots, metrics) could be shared.
The goal: one Werkator instance serves a *set* of repositories — one service, one port, one UI — while each repository keeps its own configuration, secrets, history, and artifacts.

## The Guiding Idea: the Repository Stays Self-Contained

Everything repository-specific already lives *inside* the repository: the machine config with secrets in `.git/werkator/`, build results, auto-build slots, worktrees, buildenvs — and the artifact store is already keyed per repo path.
The multi-repo instance therefore does not absorb repository state; it becomes an *aggregator* over self-contained repositories.
Consequences:

- Adding or removing a repository is editing a registry entry, never a data migration.
- A repository can move between instances (or back to its own) without losing anything.
- Single-repo mode stays the degenerate case: a registry of one, implicitly the current working directory — existing installations keep working without any config change.

## Key Ownership Splits

Today all config comes from the repo's own layers; multi-repo splits ownership:

- **Instance-level** (decided 2026-09-01: a `.werkator.yml` in the *home directory* of the user running the instance — the name stays `.werkator.yml` in all three locations, the location carries the meaning): `server.*` (port, bind address, public base URL / domain, nginx), the repository registry, the control token (one UI, one token), `executor.maxConcurrent` as the *global* cap, watcher interval, metrics.
- **Repo defaults** (decided 2026-09-01): the home file MAY carry defaults for repo-level keys (e.g. one `git.account`/`git.token` for all repos of the same forge), in an explicit `defaults:` block so instance keys and repo defaults never blur syntactically.
  The block merges BELOW every repo's own layers: home `defaults` → committed project config → repo machine config → branch layer (pinning semantics unchanged — home and repo machine config are both host-side layers, the branch layer still cannot reach pinned keys).
  Accepted cost: secrets may then live in two places; a repo without its own secrets is no longer self-contained on its own.
- **Repo-level** (unchanged, from the repo's own layers — machine config in its `.git/werkator/`, committed project config, branch layer): `gitea.*` (each repo has its own owner/repo/token/statusContext), `git.*` credentials, `builds`, retention, per-repo watcher options (e.g. `pullRequestGate`), sandbox policy and its pinning.
- **Both**: a per-repo concurrency cap below the global one may come later; not in the first cut.

The pinning model is untouched: pinned keys still come from each repo's machine config, and the branch layer still cannot reach them.

## The Sessions

### A — Decision and schema (ADR 0009)

- ~~Write ADR 0009~~ — done 2026-09-01: `docs/adrs/0009-2026-09-01.multi-repo-instance.md` revises the one-instance-per-repository tenet to one-instance-per-*set* and records the aggregator idea, the key ownership split, the four 2026-09-01 decisions, and the rejected federation-dashboard alternative.
- Define the instance config: `~/.werkator.yml` (decided 2026-09-01) — the repository registry plus the instance-level keys above; one instance per OS user, which matches the platform model (pac users on a webspace, service users elsewhere).
  `werkator server` without a home config serves the current directory exactly as today.
- Decide the transition for instance keys that today sit in a repo's machine config (mih34's carries `server.*`): once a home config exists, repo-level instance keys are ignored with a warning naming both files — never merged silently.
- Repo identity for display and routes (decided 2026-09-01): a short unique name per registry entry, defaulting to the repository's directory basename, overridable in the entry; duplicate resulting names abort the start loudly. Used as the route segment (`/repos/<name>/…`) and UI grouping key.
- Precedence (decided 2026-09-01): when a home config with a registry exists, `werkator server` serves the registry regardless of the current directory — one user, one instance, deterministic; without a home config it serves the current directory exactly as today.
- Update `docs/Werkator-Konzept.md` and the AGENTS.md architecture wording ("one instance per repository set") when the implementation lands (sessions B–D) — until then the existing behavior description remains accurate; the AGENTS.md decision list carries ADR 0009 already.

### B — RepoContext refactor, behavior unchanged

- ~~Introduce a `RepoContext` (working dir, config loading, git access, result repository, artifact store key, watcher state) and thread it through executor, watcher, and server code paths that today implicitly use the single `workingDir`.~~ — done 2026-09-02 (PR #11): `RepoContext` (`repo` package) carries `name`, `workingDir`, `results`, `artifactStore`; git access and config loading stay path-based services taking `repo.workingDir` (the home `defaults:` layer of session C is the moment config loading needs the context). The watcher's per-repo memory lives in a `RepoWatch` keyed by context; `WatcherState` stays one per instance until session C.
- ~~The executor becomes instance-global with repo-scoped pools: serialization per (repo, branch), the global `maxConcurrent` across repos; `BuildResult` needs no schema change — results stay in each repo's own JSON file, the repo dimension exists only in memory and in routes.~~ — done 2026-09-02: `startBuild(repo, branch, commit, build)`, pools keyed by (context, branch), one semaphore.
- ~~Single-repo behavior, routes, and UI stay byte-identical; the full test suite is the acceptance gate.~~ — done: no route, template, or config change; the current-directory context is a bean and the result/artifact-store beans are its members.
- Carried over to session C (found while threading): `StateDirMigration` runs once per process on the cwd and must run per registered repo; `SystemMetricsCollector` measures the cwd's repository size; `ServerCommand` reads `server.*` from the cwd; `RunningBuild` carries no repository, so `BuildExecutor.currentBuilds()` and the watcher's worktree pruning cannot tell repos apart yet (harmless today: at worst a worktree of another repo's branch name is kept one cycle longer).

### C — The registry and N repositories

- ~~Load the registry, build one `RepoContext` per entry; fail the start loudly on duplicate names or unreadable repos (config-version violations abort only that repo's registration, like branch-config violations fail only that branch).~~ — done 2026-09-02: `InstanceConfig` binds `~/.werkator.yml` (`ConfigLoader.homeDir`, `WERKATOR_HOME` overrides); `RepoRegistry` opens the contexts. The instance keys and the `defaults` block are applied inside `ConfigLoader.loadRaw`, so every `load(dir)` consumer sees them without knowing the file — the repository's copies of instance keys are dropped with one warning naming both files.
- ~~Watcher multiplexing: one poll cycle iterates the contexts (fetch, enqueue, prune per repo) with per-repo error isolation — one unreachable origin must not starve the others; `WatcherState` gains the repo dimension for the health banner.~~ — done 2026-09-02: `pollAll(repos)`, one guard per repository, `WatcherState.repositories`; the top-level fields aggregate (unchanged with one repository, name-prefixed with several). Isolation proven by test (one unreachable origin, the other still enqueues).
- ~~Startup recovery per repo; auto-build slots stay in each repo's `.git/werkator/`.~~ — done: `start(repos)` recovers each in its own guard; slots unchanged.
- ~~CLI commands gain an optional repo selector and default to the current working directory, so `werkator status` inside a repo behaves as today.~~ — done: `--repo <name>` (`RepoOption` mixin) on `build`, `retry`, `status`; default is the cwd when served, else the first registered repository.
- Also done: the pre-rename state-dir migration runs per opened repository; the metrics page's repository size sums the registered repositories (the disk metric is the first one's file store).
- Carried over to session D: ~~`RunningBuild` still carries no repository (the "current builds" view and the worktree pruning cannot tell repositories apart)~~ — done 2026-09-03: `RunningBuild.repo` is the context (identity comparison), the current-builds view and API filter to the served repository, and the worktree pruning is protected by its own repository's running builds alone; the controllers still serve `registry.current()` only; `docs/deployment.md` gets the registry setup with session E.

### D — Server, API, and UI scoping

- Routes gain the repo segment (`/api/repos/<name>/builds/…`, `/repos/<name>/builds/<key>`); with exactly one registered repo the today-routes keep working (redirect or alias) so bookmarks and posted Gitea links survive.
- Latest/branches/history views group by repo or gain a repo column; one instance-wide metrics page; one control token.
- Gitea status links use the repo-scoped URLs.

### E — Rollout on mih34: Werkbaum joins

- Registry with the Werkator and Werkbaum repositories under the existing user, one service, one port, the existing tunnel.
- Write Werkbaum's `.werkator.yml`: Gradle backend build and npm frontend build in the shared trimmed image (Node is already in it).
- Record the deployment; retire the second-instance/second-user idea from the notes.

## Open Questions

- ~~Fairness across repos when the global concurrency cap is contended (round-robin per repo vs. FIFO) — decide in session C with the real queue behavior at hand.~~ — decided 2026-09-02: FIFO. The executor's slot semaphore is already fair, so builds take slots in enqueue order across repositories; the watcher enqueues in registry order within one cycle, which is a fixed and inspectable bias rather than a scheduler. Round-robin per repository only when a real queue shows starvation.
- Whether buildenv rootfs trees should be shared across repos (today each repo unpacks its own under `.git/werkator/buildenv/`) — the natural answer is Werkdock's image store (step 21 session C), not instance-level state; until then duplicate unpacked rootfs trees are the accepted cost.
- ~~Whether `artifactKey` needs a repo prefix or stays globally unique by construction (random suffix) — decide in session B when the routes are designed.~~ — decided 2026-09-02: no prefix. The key is derived from pool name and start time, and both the results file and the artifact store are per repository, so it only ever has to be unique within one; the repo dimension enters through the route segment in session D, never through the key. A prefix would also change every existing artifact directory name.

## Acceptance Criteria

- Session A: ADR 0009 written (done 2026-09-01); the registry and key ownership land in `docs/configuration.md` together with the implementing sessions, since that reference describes implemented configuration only.
- ~~Session B: full suite green with `RepoContext` threaded through; no route or behavior change observable.~~ — done 2026-09-02.
- ~~Session C: an instance with two registered repos builds pushes in both, with per-repo error isolation proven by a test (one broken origin, the other keeps building).~~ — done 2026-09-02 (`WatcherTest`: "one repository's unreachable origin neither stops nor silences the other").
- Session D: both repos browsable in one UI; single-repo installations keep their existing URLs.
- Session E: mih34 builds Werkator and Werkbaum from one service; `docs/deployment.md` describes the registry setup.
