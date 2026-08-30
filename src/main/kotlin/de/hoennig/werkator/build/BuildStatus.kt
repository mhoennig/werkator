package de.hoennig.werkator.build

enum class BuildStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    INTERRUPTED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this != PENDING && this != RUNNING

    val isRestartable: Boolean
        get() = this == PENDING || this == RUNNING || this == INTERRUPTED
}
