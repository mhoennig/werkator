package de.hoennig.werkator.server

/**
 * A route named a repository this instance does not serve (ADR 0009). Thrown by the
 * repository-scoped controllers and turned into their own 404 shape by their exception
 * handlers — a name that is simply not registered is a miss like any other, not a
 * server error.
 */
class UnknownRepositoryException(
    val name: String,
) : RuntimeException("no repository named '$name'")
