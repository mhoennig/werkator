package de.hoennig.gittally.server

import de.hoennig.gittally.build.ArtifactStore
import de.hoennig.gittally.build.BuildExecutor
import de.hoennig.gittally.build.BuildResult
import de.hoennig.gittally.build.BuildResultRepository
import de.hoennig.gittally.build.BuildStatus
import de.hoennig.gittally.config.ConfigLoader
import de.hoennig.gittally.metrics.SystemMetricsCollector
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.view.RedirectView
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Server-rendered Thymeleaf views over the JSON API. The pages render the full
 * state server-side (usable without JavaScript); `gittally.js` then polls the
 * `/api/…` endpoints and re-renders the table bodies — pages are never re-fetched
 * and diffed like legacy, so the UI cannot get stuck on a loading animation.
 */
@Controller
class UiController(
    private val repository: BuildResultRepository,
    private val buildExecutor: BuildExecutor,
    private val artifactStore: ArtifactStore,
    private val controlTokens: ControlTokenService,
    private val configLoader: ConfigLoader,
    private val metricsCollector: SystemMetricsCollector,
    private val branchListing: BranchListing,
    private val branchPermalinks: BranchPermalinks,
    private val buildProperties: ObjectProvider<BuildProperties>,
) {
    var workingDir: Path = Paths.get(".")

    /**
     * Permanent redirects for the legacy script's static page names, so bookmarks
     * and links from before the rewrite (e.g. via the old host's redirect) keep working.
     */
    @GetMapping("/index.html", "/branches.html", "/history.html", "/system.html", "/about.html", "/license.html")
    fun legacyPageName(request: HttpServletRequest): RedirectView =
        RedirectView(LEGACY_PAGE_TARGETS.getValue(request.requestURI)).apply {
            setStatusCode(HttpStatus.MOVED_PERMANENTLY)
        }

    @GetMapping("/")
    fun latest(model: Model): String {
        val links = baseModel(model, view = "latest", pageTitle = "Latest Builds")
        model.addAttribute("rows", repository.latestPerBranch().map { BuildRowView.from(it, links) })
        model.addAttribute("apiPath", "/api/builds/latest")
        model.addAttribute("allowRestart", true)
        model.addAttribute("emptyMessage", "No builds recorded yet.")
        return "builds"
    }

    /** The legacy branches view: every origin branch with its latest build or an `unknown` row. */
    @GetMapping("/branches")
    fun branches(model: Model): String {
        val links = baseModel(model, view = "branches", pageTitle = "Branches")
        model.addAttribute("rows", branchListing.branches(workingDir).map { BuildRowView.from(it, links) })
        model.addAttribute("apiPath", "/api/branches")
        model.addAttribute("allowRestart", true)
        model.addAttribute("emptyMessage", "No branches found on origin.")
        return "builds"
    }

    @GetMapping("/history")
    fun history(model: Model): String {
        val links = baseModel(model, view = "history", pageTitle = "Build History")
        model.addAttribute("rows", repository.history().map { BuildRowView.from(it, links) })
        model.addAttribute("apiPath", "/api/builds/history")
        model.addAttribute("allowRestart", false)
        model.addAttribute("emptyMessage", "No builds archived yet.")
        return "builds"
    }

    @GetMapping("/current")
    fun current(model: Model): String {
        val links = baseModel(model, view = "current", pageTitle = "Current Builds")
        val results = repository.history()
        val currentBuilds =
            buildExecutor.currentBuilds().map { build ->
                CurrentBuildView(
                    branch = build.branch,
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

    @GetMapping("/system")
    fun system(model: Model): String {
        baseModel(model, view = "system", pageTitle = "System Metrics")
        model.addAttribute("metrics", SystemMetricsView.from(metricsCollector.snapshot()))
        return "system"
    }

    /** Artifact index rendered from the artifact store — legacy pre-generated this page as static HTML. */
    @GetMapping("/builds/{artifactKey}")
    fun artifactIndex(
        @PathVariable artifactKey: String,
        model: Model,
    ): String {
        val result = repository.history().firstOrNull { it.artifactKey == artifactKey }
        val artifactDir = artifactStore.artifactDir(artifactKey)
        if (result == null && artifactDir == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "no build with artifact key '$artifactKey'")
        }
        return artifactIndexView(
            model,
            pageTitle = "Build Artifacts",
            result = result,
            artifactKey = artifactKey,
            artifactDir = artifactDir,
            filesBase = "/artifacts/$artifactKey",
        )
    }

    /**
     * The permanent artifact index of a branch's latest green build; the file links
     * stay on the permanent `/branches/…` paths, so every link copied from this page
     * outlives artifact pruning.
     */
    @GetMapping("/branches/{branchKey}")
    fun latestGreenArtifactIndex(
        @PathVariable branchKey: String,
        model: Model,
    ): String {
        val build = branchPermalinks.latestGreenBuild(branchKey)
        model.addAttribute("permanentBranch", build.branch)
        model.addAttribute("concreteUrl", "/builds/${build.artifactKey}")
        return artifactIndexView(
            model,
            pageTitle = "Latest Green Build",
            result = build,
            artifactKey = build.artifactKey,
            artifactDir = artifactStore.artifactDir(build.artifactKey),
            filesBase = "/branches/$branchKey",
        )
    }

    private fun artifactIndexView(
        model: Model,
        pageTitle: String,
        result: BuildResult?,
        artifactKey: String,
        artifactDir: Path?,
        filesBase: String,
    ): String {
        val links = baseModel(model, view = "artifact", pageTitle = pageTitle)
        model.addAttribute("artifactKey", artifactKey)
        model.addAttribute("filesBase", filesBase)
        model.addAttribute("result", result?.let { BuildRowView.from(it, links) })
        model.addAttribute("hasArtifacts", artifactDir != null)
        model.addAttribute("buildCommand", result?.let { branchBuildCommand(it.branch) })
        model.addAttribute("logs", artifactDir?.let { logFiles(it) } ?: emptyList<String>())
        model.addAttribute("reportIndexes", artifactDir?.let { reportIndexes(it) } ?: emptyList<String>())
        return "artifact"
    }

    /** Adds the attributes every page needs and returns the Gitea link helper for row building. */
    private fun baseModel(
        model: Model,
        view: String,
        pageTitle: String,
    ): GiteaWebLinks {
        val config = configLoader.load(workingDir)
        val links = GiteaWebLinks(config.gitea)
        val repoName =
            listOf(config.gitea.owner.trim(), config.gitea.repo.trim())
                .filter { it.isNotEmpty() }
                .joinToString("/")
        model.addAttribute("view", view)
        model.addAttribute("pageTitle", pageTitle)
        model.addAttribute("repoName", repoName)
        model.addAttribute("version", buildProperties.getIfAvailable()?.version ?: "dev")
        model.addAttribute("impressumUrl", config.server.impressumUrl.trim())
        model.addAttribute("controlToken", controlTokens.token())
        model.addAttribute("giteaRepoUrl", links.repoUrl ?: "")
        return links
    }

    /** The currently configured build command — the command actually used at build time is not persisted. */
    private fun branchBuildCommand(branch: String): String {
        val branches = configLoader.load(workingDir).branches
        return (branches[branch] ?: branches["default"])?.buildCommand ?: ""
    }

    /** The stored log files: all top-level regular files of the artifact directory. */
    private fun logFiles(artifactDir: Path): List<String> =
        Files.list(artifactDir).use { children ->
            children
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .map { it.name }
                .sorted()
                .toList()
        }

    /**
     * The browsable `index.html` pages under `reports/`, shallowest first; pages nested
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
            if (knownDirs.any { known -> known.isEmpty() || dir == known || dir.startsWith("$known/") }) {
                continue
            }
            knownDirs += dir
            topmost += relativeIndex
        }
        return topmost.map { ReportIndexView(path = it, failures = reportFailures(reportsDir.resolve(it))) }
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
        private val FAILURES_COUNTER = Regex("""id="failures">\s*<div class="counter">(\d+)""")

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
