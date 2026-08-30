package de.hoennig.werkator.commands

import java.nio.file.Path

/**
 * Generates the content of the systemd user unit and its `EnvironmentFile` for running
 * `werkator server` as a service — the shape of the legacy `generate_systemd_config`,
 * without the self-copy/self-update machinery (the unit points at the jar in place).
 */
object SystemdServiceFiles {
    const val ENV_FILE_NAME = "werkator.env"

    /** Host-global unit names of the nightly Docker cleanup — shared by all werkator repositories on the host. */
    const val PRUNE_SERVICE_NAME = "werkator-docker-prune.service"
    const val PRUNE_TIMER_NAME = "werkator-docker-prune.timer"

    /** Per-repository unit name, because one werkator instance serves exactly one repository. */
    fun unitName(repoRoot: Path): String = "werkator-${sanitize(repoRoot.fileName.toString())}.service"

    fun unitFileContent(
        repoRoot: Path,
        javaExecutable: Path,
        jarPath: Path,
        envFile: Path,
    ): String =
        """
        [Unit]
        Description=werkator CI for ${repoRoot.fileName}
        Wants=network-online.target
        After=network-online.target docker.service

        [Service]
        Type=simple
        WorkingDirectory=${systemdPath("$repoRoot")}
        EnvironmentFile=-${systemdPath("$envFile")}
        ExecStart=${systemdQuote("$javaExecutable")} ${'$'}JAVA_OPTS -jar ${systemdQuote("$jarPath")} server
        Restart=always
        RestartSec=30

        [Install]
        WantedBy=default.target
        """.trimIndent() + "\n"

    /**
     * Nightly Docker cleanup like the legacy `docker-prune.service`, but without `--volumes`:
     * the per-repository Gradle cache volumes must survive the cleanup.
     * Running containers and their images are never pruned, so an in-flight build is safe.
     */
    fun pruneServiceContent(): String =
        """
        [Unit]
        Description=Clean up unused Docker containers and images (werkator)

        [Service]
        Type=oneshot
        # skipped (not failed) on hosts without a docker CLI
        ExecCondition=sh -c 'command -v docker'
        ExecStart=docker system prune -af
        """.trimIndent() + "\n"

    /** Fires before the usual auto-build slots, `Persistent=true` catches missed runs after downtime. */
    fun pruneTimerContent(): String =
        """
        [Unit]
        Description=Nightly Docker cleanup before the auto builds (werkator)

        [Timer]
        OnCalendar=*-*-* 02:00:00
        Persistent=true

        [Install]
        WantedBy=timers.target
        """.trimIndent() + "\n"

    fun envFileContent(): String =
        """
        # EnvironmentFile for the werkator systemd service.
        # werkator itself is configured via .werkator.yml and .git/werkator/.werkator.yml,
        # not via environment variables; this file only tunes the JVM process.
        #JAVA_OPTS=-Xmx256m
        """.trimIndent() + "\n"

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9_.-]"), "-")

    /** Escape `%` specifiers in systemd unit values (legacy `systemd_path`). */
    private fun systemdPath(value: String): String = value.replace("%", "%%")

    /** Quote one `ExecStart` word (legacy `systemd_quote`). */
    private fun systemdQuote(value: String): String =
        "\"" +
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("%", "%%") +
            "\""
}
