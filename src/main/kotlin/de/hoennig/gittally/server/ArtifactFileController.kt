package de.hoennig.gittally.server

import de.hoennig.gittally.build.ArtifactStore
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
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
) {
    @GetMapping("/artifacts/{artifactKey}/{*path}")
    fun serve(
        @PathVariable artifactKey: String,
        @PathVariable path: String,
    ): ResponseEntity<Resource> {
        val artifactDir =
            artifactStore.artifactDir(artifactKey)
                ?: return ResponseEntity.notFound().build()
        val relativePath = path.removePrefix("/")
        if (relativePath.isBlank()) {
            return ResponseEntity.notFound().build()
        }
        val file = artifactDir.resolve(relativePath).normalize()
        if (!file.startsWith(artifactDir) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return ResponseEntity.notFound().build()
        }
        val headers = HttpHeaders()
        headers.contentType = mediaType(file)
        if (file.extension() in NO_CACHE_EXTENSIONS) {
            headers.cacheControl = "no-store, max-age=0"
            headers.pragma = "no-cache"
            headers.expires = 0
        }
        return ResponseEntity.ok().headers(headers).body(FileSystemResource(file))
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
    }
}
