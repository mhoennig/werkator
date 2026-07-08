package de.hoennig.gittally.server

import de.hoennig.gittally.build.ArtifactStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Streams stored build artifacts. Status pages, JSON, and logs are served with
 * no-cache headers like legacy, so browsers always see the current build state.
 */
@RestController
class ArtifactFileController(
    private val artifactStore: ArtifactStore,
    private val branchPermalinks: BranchPermalinks,
) {
    @GetMapping("/artifacts/{artifactKey}/{*path}")
    fun serve(
        @PathVariable artifactKey: String,
        @PathVariable path: String,
    ): ResponseEntity<Resource> {
        val artifactDir =
            artifactStore.artifactDir(artifactKey)
                ?: return ResponseEntity.notFound().build()
        val file =
            resolveFile(artifactDir, path.removePrefix("/"))
                ?: return ResponseEntity.notFound().build()
        return respond(file, noStore = file.extension() in NO_CACHE_EXTENSIONS)
    }

    /**
     * Permanent artifact URLs: serves the file from the branch's latest green build,
     * so the URL outlives artifact pruning as long as the branch stays green-buildable.
     * Directory paths serve their `index.html` (after a redirect adding the trailing
     * slash, so relative links inside reports resolve correctly), and everything is
     * `no-store` because the content behind a URL changes with every new green build.
     */
    @GetMapping("/branches/{branchKey}/{*path}")
    fun serveLatestGreen(
        @PathVariable branchKey: String,
        @PathVariable path: String,
        request: HttpServletRequest,
    ): ResponseEntity<Resource> {
        val build = branchPermalinks.latestGreenBuild(branchKey)
        val artifactDir =
            artifactStore.artifactDir(build.artifactKey)
                ?: throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "the artifacts of build '${build.artifactKey}' are not stored anymore",
                )
        val relativePath = path.removePrefix("/").removeSuffix("/")
        if (relativePath.isBlank()) {
            // the bare permanent URL is the artifact-index page rendered by the UI controller
            return redirect(request.requestURI.trimEnd('/'))
        }
        val target = artifactDir.resolve(relativePath).normalize()
        if (target.startsWith(artifactDir) &&
            Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(target.resolve(INDEX_FILE), LinkOption.NOFOLLOW_LINKS)
        ) {
            // relative links inside a report only resolve correctly under a trailing-slash URL
            return if (request.requestURI.endsWith("/")) {
                respond(target.resolve(INDEX_FILE), noStore = true)
            } else {
                redirect(request.requestURI + "/")
            }
        }
        val file =
            resolveFile(artifactDir, relativePath)
                ?: return ResponseEntity.notFound().build()
        return respond(file, noStore = true)
    }

    /** Resolves [relativePath] inside [artifactDir]; null when it escapes the directory or is no regular file. */
    private fun resolveFile(
        artifactDir: Path,
        relativePath: String,
    ): Path? {
        if (relativePath.isBlank()) {
            return null
        }
        val file = artifactDir.resolve(relativePath).normalize()
        if (!file.startsWith(artifactDir) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        return file
    }

    private fun respond(
        file: Path,
        noStore: Boolean,
    ): ResponseEntity<Resource> {
        val headers = HttpHeaders()
        headers.contentType = mediaType(file)
        if (noStore) {
            headers.cacheControl = "no-store, max-age=0"
            headers.pragma = "no-cache"
            headers.expires = 0
        }
        return ResponseEntity.ok().headers(headers).body(FileSystemResource(file))
    }

    /** A permanent-URL redirect must never be cached — its target changes with the next green build. */
    private fun redirect(encodedLocation: String): ResponseEntity<Resource> {
        val headers = HttpHeaders()
        headers.location = URI.create(encodedLocation)
        headers.cacheControl = "no-store, max-age=0"
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build()
    }

    private fun mediaType(file: Path): MediaType =
        when (file.extension()) {
            "log" -> MediaType(MediaType.TEXT_PLAIN, Charsets.UTF_8)
            else ->
                MediaTypeFactory
                    .getMediaType(file.fileName.toString())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM)
        }

    private fun Path.extension(): String = fileName.toString().substringAfterLast('.', "").lowercase()

    companion object {
        private val NO_CACHE_EXTENSIONS = setOf("html", "json", "log")
        private const val INDEX_FILE = "index.html"
    }
}
