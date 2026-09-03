package de.hoennig.werkator.repo

import org.springframework.stereotype.Component

/**
 * The path prefix a link to a repository carries (ADR 0009). One place knows the
 * rule, because three do the same thing with it: the pages, the API answers, and
 * the target URLs posted to Gitea — and a rule spelled out three times is a rule
 * that drifts.
 *
 * It follows the NUMBER of served repositories, not the route a request arrived
 * through: with one repository an installation keeps the URLs it always had, with
 * several every link names its repository.
 */
@Component
class RepoLinks(
    private val registry: RepoRegistry,
) {
    fun base(repo: RepoContext): String = if (registry.all().size > 1) "/repos/${repo.name}" else ""

    fun apiBase(repo: RepoContext): String = if (registry.all().size > 1) "/api/repos/${repo.name}" else "/api"

    /**
     * The absolute artifact-page URL of a build, for links Werkator posts elsewhere
     * (`server.publicBaseUrl`). Null without a public base URL: a relative link in a
     * Gitea status is worse than none — it would resolve against the forge.
     */
    fun buildUrl(
        repo: RepoContext,
        publicBaseUrl: String,
        artifactKey: String,
    ): String? {
        val root = publicBaseUrl.trim().trimEnd('/')
        if (root.isEmpty()) return null
        return "$root${base(repo)}/builds/$artifactKey"
    }
}
