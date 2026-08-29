package de.hoennig.gittally.config

/**
 * The GitTally version a configuration file declares itself for, the `gitTally.version`
 * section:
 *
 * ```yaml
 * gitTally:
 *   version:
 *     since: "0.9.16"   # always hard: an older GitTally refuses this file
 *     below: "2.0"      # GitTally decides how hard, see ConfigVersions.verdict
 * ```
 *
 * There is deliberately no version of the file format itself (no `apiVersion`): no API is
 * involved — GitTally reads its own configuration — and only one configuration generation
 * is ever supported. The declared version exists to make an incompatibility nameable,
 * never to run two parsers.
 */
data class VersionRequirement(
    /** Oldest GitTally that understands this file; empty means the file does not say. */
    val since: String = "",
    /** First GitTally this file was not released for; empty means no ceiling. */
    val below: String = "",
)

data class GitTallyMeta(
    val version: VersionRequirement = VersionRequirement(),
)

/** What a [VersionRequirement] means for the GitTally that reads the file. */
sealed interface VersionVerdict {
    /** The running version is covered by the declaration. */
    data object Compatible : VersionVerdict

    /** Usable, but the file was not released for this version. */
    data class Warn(
        val message: String,
    ) : VersionVerdict

    /** Not usable: the file predates a change that GitTally cannot bridge. */
    data class Incompatible(
        val message: String,
    ) : VersionVerdict
}

/** A configuration file this GitTally must not read; carries the file's name in its message. */
open class ConfigException(
    message: String,
) : RuntimeException(message)

/** The file declares a GitTally that cannot read it, see [ConfigVersions]. */
class ConfigVersionException(
    message: String,
) : ConfigException(message)

/**
 * The file is written in a shape this GitTally no longer reads. Refusing it is the point:
 * a key that moved and is silently ignored means a build that quietly stops happening.
 */
class ConfigFormatException(
    message: String,
) : ConfigException(message)

object ConfigVersions {
    /**
     * The version in which the configuration format last changed incompatibly — a file
     * written before it cannot be read by this GitTally. Empty while no such change has
     * happened; set it to the release that introduces one, together with the migration
     * note the message points at.
     */
    const val FORMAT_BROKE_IN = ""

    /** Human-readable description of that change, shown in the error message. */
    const val FORMAT_BROKE_DESCRIPTION = ""

    /**
     * Decides what [requirement] means for [running].
     *
     * `since` is always hard — a file that needs a newer GitTally cannot be honored, and
     * silently ignoring its unknown keys is exactly the failure mode this section exists
     * to prevent.
     *
     * `below` alone only warns: it is the team's release marker, and an unmaintained
     * marker must never stop a CI. Whether the running version really broke the file is
     * GitTally's own knowledge ([FORMAT_BROKE_IN]) — a file written before that change
     * and read after it is incompatible regardless of what it declares as its ceiling.
     */
    fun verdict(
        requirement: VersionRequirement,
        running: String?,
        brokeIn: String = FORMAT_BROKE_IN,
        brokeDescription: String = FORMAT_BROKE_DESCRIPTION,
    ): VersionVerdict {
        val version = parse(running) ?: return VersionVerdict.Compatible
        val since = parse(requirement.since)
        if (since != null && version < since) {
            return VersionVerdict.Incompatible(
                "needs GitTally ${requirement.since} or newer (gitTally.version.since), this is $running",
            )
        }
        val broke = parse(brokeIn)
        if (since != null && broke != null && since < broke && version >= broke) {
            return VersionVerdict.Incompatible(
                "is written for GitTally ${requirement.since} (gitTally.version.since), " +
                    "but the configuration format changed incompatibly in $brokeIn" +
                    brokeDescription.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
            )
        }
        val below = parse(requirement.below)
        if (below != null && version >= below) {
            return VersionVerdict.Warn(
                "was released for GitTally below ${requirement.below} (gitTally.version.below), this is $running",
            )
        }
        return VersionVerdict.Compatible
    }

    /**
     * `1.2.3` and shorter prefixes like `2.0`, compared numerically part by part with
     * missing parts as 0 — `below: "2.0"` is the point 2.0.0, which is why the ceiling is
     * exclusive: an inclusive one could not tell `1` (the release) from `1.x` (the series).
     * A pre-release suffix (`1.0.0-rc1`) is ignored, and anything unparseable yields null,
     * so a typo can never make a file look incompatible.
     */
    fun parse(version: String?): List<Int>? {
        val text = version?.trim()?.substringBefore('-').orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val parts = text.split('.').map { it.toIntOrNull() ?: return null }
        return (parts + listOf(0, 0, 0)).take(3)
    }

    private operator fun List<Int>.compareTo(other: List<Int>): Int =
        indices.firstNotNullOfOrNull { i -> (this[i] - other[i]).takeIf { it != 0 } } ?: 0
}
