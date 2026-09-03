package de.hoennig.werkator.server

import de.hoennig.werkator.build.BuildExecutor
import de.hoennig.werkator.build.BuildResult
import de.hoennig.werkator.build.BuildStatus
import de.hoennig.werkator.config.ConfigFiles
import de.hoennig.werkator.config.ConfigLoader
import de.hoennig.werkator.git.GitService
import de.hoennig.werkator.metrics.SystemMetricsCollector
import de.hoennig.werkator.repo.RepoContext
import de.hoennig.werkator.repo.RepoLinks
import de.hoennig.werkator.repo.RepoRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.view.RedirectView
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Server-rendered Thymeleaf views over the JSON API. The pages render the full
 * state server-side (usable without JavaScript); `werkator.js` then polls the
 * `/api/…` endpoints and re-renders the table bodies — pages are never re-fetched
 * and diffed like legacy, so the UI cannot get stuck on a loading animation.
 */
@Controller
class UiController(
    private val buildExecutor: BuildExecutor,
    private val configLoader: ConfigLoader,
    private val gitService: GitService,
    private val metricsCollector: SystemMetricsCollector,
    private val branchListing: BranchListing,
    private val branchPermalinks: BranchPermalinks,
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val registry: RepoRegistry,
    private val repoLinks: RepoLinks,
) {
    /**
     * Every page exists twice, like the API (ADR 0009): repository-scoped under
     * `/repos/<name>/…` and unscoped, which means the served repository. Pages stay
     * per repository instead of merging every repository's rows into one table with a
     * repository column: a row's actions (restart, cancel, delete) need the repository
     * anyway, branches come from one origin and artifacts from one store — and with
     * the one repository that most installations have, such a column is pure noise.
     * What makes the instance one UI is the repository switcher in the navigation.
     */
    private fun repoOf(name: String?): RepoContext =
        if (name == null) registry.current() else registry.byName(name) ?: throw UnknownRepositoryException(name)

    /** An unknown repository name is a 404 page, not a server error. */
    @ExceptionHandler(UnknownRepositoryException::class)
    fun unknownRepository(e: UnknownRepositoryException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.message)

    /**
     * Permanent redirects for the legacy script's static page names, so bookmarks
     * and links from before the rewrite (e.g. via the old host's redirect) keep working.
     */
    @GetMapping("/index.html", "/branches.html", "/history.html", "/system.html", "/about.html", "/license.html")
    fun legacyPageName(request: HttpServletRequest): RedirectView =
        RedirectView(LEGACY_PAGE_TARGETS.getValue(request.requestURI)).apply {
            setStatusCode(HttpStatus.MOVED_PERMANENTLY)
        }

    @GetMapping("/", "/repos/{repo}")
    fun latest(
        @PathVariable(name = "repo", required = false) repoName: String?,
        model: Model,
    ): String {
        val repo = repoOf(repoName)
        val links = baseModel(model, view = "latest", pageTitle = "Latest Builds", repo = repo)
        model.addAttribute("rows", repo.results.latestPerName().map { BuildRowView.from(it, links, permanentUrlOf(repo, it)) })
        model.addAttribute("apiPath", apiBase(repo) + "/builds/latest")
        model.addAttribute("allowRestart", true)
        model.addAttribute("restartAtOriginHead", false)
        model.addAttribute("emptyMessage", "No builds recorded yet.")
        return "builds"
    }

    /** The legacy branches view: every origin branch with its latest build or an `unknown` row. */
    @GetMapping("/branches", "/repos/{repo}/branches")
    fun branches(
        @PathVariable(name = "repo", required = false) repoName: String?,
        model: Model,
    ): String {
        val repo = repoOf(repoName)
        val links = baseModel(model, view = "branches", pageTitle = "Branches", repo = repo)
        model.addAttribute("rows", branchListing.branches(repo, uiBase(repo)).map { BuildRowView.from(it, links) })
        model.addAttribute("apiPath", apiBase(repo) + "/branches")
        model.addAttribute("allowRestart", true)
        // a row here stands for a branch, not for a past run
        model.addAttribute("restartAtOriginHead", true)
        model.addAttribute("emptyMessage", "No branches found on origin.")
        return "builds"
    }

    @GetMapping("/history", "/repos/{repo}/history")
    fun history(
        @PathVariable(name = "repo", required = false) repoName: String?,
        model: Model,
    ): String {
        val repo = repoOf(repoName)
        val links = baseModel(model, view = "history", pageTitle = "Build History", repo = repo)
        model.addAttribute("rows", repo.results.history().map { BuildRowView.from(it, links, permanentUrlOf(repo, it)) })
        model.addAttribute("apiPath", apiBase(repo) + "/builds/history")
        model.addAttribute("allowRestart", false)
        model.addAttribute("restartAtOriginHead", false)
        model.addAttribute("emptyMessage", "No builds archived yet.")
        return "builds"
    }

    /** The permanent branch URL belongs to the build it resolves to — the name's latest green build. */
    private fun permanentUrlOf(
        repo: RepoContext,
        result: BuildResult,
    ): String? =
        if (repo.results.latestGreenFor(result.name)?.artifactKey == result.artifactKey) {
            BranchPermalinks.permanentUrl(result.name, uiBase(repo))
        } else {
            null
        }

    @GetMapping("/current", "/repos/{repo}/current")
    fun current(
        @PathVariable(name = "repo", required = false) repoName: String?,
        model: Model,
    ): String {
        val repo = repoOf(repoName)
        val links = baseModel(model, view = "current", pageTitle = "Current Builds", repo = repo)
        val results = repo.results.history()
        val currentBuilds =
            buildExecutor.currentBuilds().filter { it.repo === repo }.map { build ->
                CurrentBuildView(
                    branch = build.branch,
                    name = build.name,
                    commit = build.commit,
                    commitAbbrev = build.commit.take(12),
                    status =
                        (results.firstOrNull { it.artifactKey == build.artifactKey }?.status ?: BuildStatus.RUNNING)
                            .jsonName,
                    startedAtIso = build.startedAt.toString(),
                    startedAt = UiFormats.timestamp(build.startedAt),
                    runningSinceIso = build.runningSince?.toString() ?: "",
                    artifactKey = build.artifactKey,
                    branchUrl = links.branchUrl(build.branch),
                    commitUrl = links.commitUrl(build.commit),
                )
            }
        model.addAttribute("currentBuilds", currentBuilds)
        return "current"
    }

    /** Hand-maintained release notes (templates/releases.html); linked from the version in the footer. */
    @GetMapping("/releases")
    fun releases(model: Model): String {
        baseModel(model, view = "releases", pageTitle = "Release Notes", repo = registry.current())
        return "releases"
    }

    /** One metrics page for the whole instance — the resources are the instance's, not a repository's. */
    @GetMapping("/system")
    fun system(model: Model): String {
        baseModel(model, view = "system", pageTitle = "System Metrics", repo = registry.current())
        model.addAttribute("metrics", SystemMetricsView.from(metricsCollector.snapshot()))
        return "system"
    }

    /** Artifact index rendered from the artifact store — legacy pre-generated this page as static HTML. */
    @GetMapping("/builds/{artifactKey}", "/repos/{repo}/builds/{artifactKey}")
    fun artifactIndex(
        @PathVariable(name = "repo", required = false) repoName: String?,
        @PathVariable artifactKey: String,
        model: Model,
    ): String {
        val repo = repoOf(repoName)
        val result = repo.results.history().firstOrNull { it.artifactKey == artifactKey }
        val artifactDir = repo.artifactStore.artifactDir(artifactKey)
        if (result == null && artifactDir == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "no build with artifact key '$artifactKey'")
        }
        return artifactIndexView(
            model,
            pageTitle = "Build Artifacts",
            repo = repo,
            result = result,
            artifactKey = artifactKey,
            artifactDir = artifactDir,
            filesBase = uiBase(repo) + "/artifacts/$artifactKey",
        )
    }

    /**
     * The permanent artifact index of a branch's latest green build; the file links
     * stay on the permanent `/branches/…` paths, so every link copied from this page
     * outlives artifact pruning.
     */
    @GetMapping("/branches/{branchKey}", "/repos/{repo}/branches/{branchKey}")
    fun latestGreenArtifactIndex(
        @PathVariable(name = "repo", required = false) repoName: String?,
        @PathVariable branchKey: String,
        model: Model,
    ): String {
        val repo = repoOf(repoName)
        val build = branchPermalinks.latestGreenBuild(repo, branchKey)
        model.addAttribute("permanentBranch", build.branch)
        model.addAttribute("concreteUrl", uiBase(repo) + "/builds/${build.artifactKey}")
        return artifactIndexView(
            model,
            pageTitle = "Latest Green Build",
            repo = repo,
            result = build,
            artifactKey = build.artifactKey,
            artifactDir = repo.artifactStore.artifactDir(build.artifactKey),
            filesBase = uiBase(repo) + "/branches/$branchKey",
        )
    }

    private fun artifactIndexView(
        model: Model,
        pageTitle: String,
        repo: RepoContext,
        result: BuildResult?,
        artifactKey: String,
        artifactDir: Path?,
        filesBase: String,
    ): String {
        val links = baseModel(model, view = "artifact", pageTitle = pageTitle, repo = repo)
        model.addAttribute("artifactKey", artifactKey)
        model.addAttribute("filesBase", filesBase)
        model.addAttribute("result", result?.let { BuildRowView.from(it, links) })
        model.addAttribute("hasArtifacts", artifactDir != null)
        model.addAttribute("buildCommand", result?.let { buildCommandOf(repo, it) })
        model.addAttribute(
            "logs",
            artifactDir?.let { logFiles(it, scanForFailure = result != null && result.status != BuildStatus.SUCCESS) }
                ?: emptyList<LogFileView>(),
        )
        model.addAttribute("reportIndexes", artifactDir?.let { reportIndexes(it) } ?: emptyList<String>())
        model.addAttribute("fileArtifacts", artifactDir?.let { fileArtifacts(it) } ?: emptyList<String>())
        return "artifact"
    }

    /**
     * Plain artifact files outside `reports/` — build outputs like binaries or
     * jars, archived at their workspace-relative paths. The top-level log files
     * have their own section. Capped so a huge output tree cannot flood the page.
     */
    private fun fileArtifacts(artifactDir: Path): List<String> =
        Files.walk(artifactDir).use { paths ->
            paths
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .map { artifactDir.relativize(it).toString() }
                .filterNot { it.startsWith("reports/") || (!it.contains('/') && it.endsWith(".log")) }
                .sorted()
                .take(MAX_FILE_ARTIFACTS)
                .toList()
        }

    /**
     * The prefix every in-page link and API path is built from. It follows the number
     * of served repositories, not the route the page was reached through: with one
     * repository the installation keeps its existing URLs (the session-D acceptance
     * criterion), with several every link names its repository.
     */
    private fun uiBase(repo: RepoContext): String = repoLinks.base(repo)

    private fun apiBase(repo: RepoContext): String = repoLinks.apiBase(repo)

    /** Adds the attributes every page needs and returns the Gitea link helper for row building. */
    private fun baseModel(
        model: Model,
        view: String,
        pageTitle: String,
        repo: RepoContext,
    ): GiteaWebLinks {
        val config = configLoader.load(repo.workingDir)
        val links = GiteaWebLinks(config.gitea)
        val repoName =
            listOf(config.gitea.owner.trim(), config.gitea.repo.trim())
                .filter { it.isNotEmpty() }
                .joinToString("/")
        val served = registry.all()
        model.addAttribute("view", view)
        model.addAttribute("pageTitle", pageTitle)
        model.addAttribute("repoName", repoName)
        // every in-page link is built from this prefix, so a scoped page stays scoped
        model.addAttribute("repoBase", uiBase(repo))
        model.addAttribute("homeUrl", uiBase(repo).ifEmpty { "/" })
        model.addAttribute("apiBase", apiBase(repo))
        model.addAttribute("repoKey", repo.name)
        // the switcher is what makes several repositories one UI; with one there is nothing to switch
        model.addAttribute("multiRepo", served.size > 1)
        model.addAttribute("repos", served.map { RepoLinkView(name = it.name, url = "/repos/${it.name}", current = it === repo) })
        model.addAttribute("version", buildProperties.getIfAvailable()?.version ?: "dev")
        model.addAttribute("impressumUrl", config.server.impressumUrl.trim())
        model.addAttribute("giteaRepoUrl", links.repoUrl ?: "")
        return links
    }

    /**
     * The command this build runs, resolved exactly like the executor resolves it: the
     * branch layer committed at the build's own commit, plus the overrides of the build
     * definition it belongs to. Reading only the primary config would show a command no
     * build of this pool ever ran — the branch and its job usually override it.
     * The command used by a past run is not persisted, so this is the current answer.
     */
    private fun buildCommandOf(
        repo: RepoContext,
        result: BuildResult,
    ): String {
        val workingDir = repo.workingDir
        val config =
            try {
                configLoader.loadWithBranchLayer(
                    workingDir,
                    ConfigFiles.readCommitted { gitService.showFileAtCommit(result.commit, it, workingDir) },
                )
            } catch (_: Exception) {
                configLoader.load(workingDir)
            }
        return config.buildSettings(result.branch, result.build).buildCommand
    }

    /**
     * The stored log files: all top-level regular files of the artifact directory.
     * With [scanForFailure] each one is searched for a failure line, so that a red build marks
     * the logs that explain it — a build split over stdout/stderr logs usually leaves the
     * failure in only some of them, and a failed test is reported far from `BUILD FAILED`.
     */
    private fun logFiles(
        artifactDir: Path,
        scanForFailure: Boolean,
    ): List<LogFileView> =
        Files.list(artifactDir).use { children ->
            children
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .sortedBy { it.name }
                .map { LogFileView(name = it.name, failed = scanForFailure && containsFailureMarker(it)) }
                .toList()
        }

    /**
     * Whether a log carries a build tool's failure line. Read as ISO-8859-1 and line by line:
     * the markers are ASCII, so no byte sequence of a build log can fail to decode, and the
     * scan stops at the first hit instead of pulling a multi-megabyte log into memory.
     */
    private fun containsFailureMarker(logFile: Path): Boolean =
        try {
            Files.newBufferedReader(logFile, StandardCharsets.ISO_8859_1).use { reader ->
                reader.lineSequence().any { FAILURE_MARKER.containsMatchIn(it) }
            }
        } catch (_: Exception) {
            false
        }

    /**
     * The browsable report pages under `reports/`, shallowest first; pages nested
     * below an already-listed report index are skipped — like the legacy artifact index.
     * Each entry carries the report's failures counter for the failed-badge.
     */
    private fun reportIndexes(artifactDir: Path): List<ReportIndexView> {
        val reportsDir = artifactDir.resolve("reports")
        if (!Files.isDirectory(reportsDir)) {
            return emptyList()
        }
        val allIndexes =
            Files.walk(reportsDir).use { paths ->
                paths
                    .asSequence()
                    .filter { Files.isRegularFile(it) && it.name == "index.html" }
                    .map { reportsDir.relativize(it).toString() }
                    .sortedWith(compareBy({ path -> path.count { it == '/' } }, { it.length }, { it }))
                    .toList()
            }
        val knownDirs = mutableListOf<String>()
        val topmost = mutableListOf<String>()
        for (relativeIndex in allIndexes) {
            val dir = relativeIndex.substringBeforeLast('/', "")
            if (dir.isCoveredBy(knownDirs)) {
                continue
            }
            knownDirs += dir
            topmost += relativeIndex
        }
        return (topmost + indexLessReportPages(reportsDir, knownDirs))
            .map { ReportIndexView(path = it, failures = reportFailures(reportsDir.resolve(it))) }
    }

    private fun String.isCoveredBy(knownDirs: List<String>): Boolean =
        knownDirs.any { known -> known.isEmpty() || this == known || this.startsWith("$known/") }

    /**
     * Report pages of directories without an `index.html`, such as Gradle's `--profile` report.
     * A directory holding a single page is linked as a directory, so that a timestamped file name
     * does not leak into the permanent `/branches/…` URLs. Only `reports/` itself and its direct
     * sub-directories are scanned, so that a report tree cannot flood the artifact index.
     */
    private fun indexLessReportPages(
        reportsDir: Path,
        knownDirs: List<String>,
    ): List<String> {
        val candidateDirs =
            buildList {
                add(reportsDir)
                Files.list(reportsDir).use { children ->
                    children.asSequence().filter { Files.isDirectory(it) }.forEach { add(it) }
                }
            }
        return candidateDirs
            .filterNot { reportsDir.relativize(it).toString().isCoveredBy(knownDirs) }
            .flatMap { dir ->
                val pages =
                    Files.list(dir).use { entries ->
                        entries
                            .asSequence()
                            .filter { Files.isRegularFile(it) && it.name.endsWith(".html") }
                            .map { reportsDir.relativize(it).toString() }
                            .toList()
                    }
                val dirPath = reportsDir.relativize(dir).toString()
                if (pages.size == 1 && dirPath.isNotEmpty()) listOf("$dirPath/") else pages
            }.sorted()
    }

    /**
     * The failures counter of a Gradle-style HTML test report index
     * (`<div class="infoBox" id="failures"><div class="counter">N</div>…`);
     * null for report pages without one, e.g. Jacoco or profile reports.
     */
    private fun reportFailures(indexFile: Path): Int? =
        try {
            FAILURES_COUNTER
                .find(Files.readString(indexFile))
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val MAX_FILE_ARTIFACTS = 200

        private val FAILURES_COUNTER = Regex("""id="failures">\s*<div class="counter">(\d+)""")

        /**
         * A failure line of a build tool or of a single test — `BUILD FAILED`, `BUILD FAILURE`,
         * `FAILURE: Build failed …`, and Gradle's per-test `SomeTest > works() FAILED`.
         * Upper case only, on purpose: a prose "failed" says nothing, the shouted word does.
         */
        private val FAILURE_MARKER = Regex("""\b(FAILED|FAILURE)\b""")

        /** Legacy page name → new route; about/license had no successor pages and land on the start page. */
        private val LEGACY_PAGE_TARGETS =
            mapOf(
                "/index.html" to "/",
                "/branches.html" to "/branches",
                "/history.html" to "/history",
                "/system.html" to "/system",
                "/about.html" to "/",
                "/license.html" to "/",
            )
    }
}
