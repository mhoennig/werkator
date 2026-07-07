package de.hoennig.gittally.commands

import java.nio.file.Path

/**
 * Generates the content of the systemd user unit and its `EnvironmentFile` for running
 * `gittally server` as a service — the shape of the legacy `generate_systemd_config`,
 * without the self-copy/self-update machinery (the unit points at the jar in place).
 */
object SystemdServiceFiles {
    const val ENV_FILE_NAME = "gittally.env"

    /** Per-repository unit name, because one GitTally instance serves exactly one repository. */
    fun unitName(repoRoot: Path): String = "gittally-${sanitize(repoRoot.fileName.toString())}.service"

    fun unitFileContent(
        repoRoot: Path,
        javaExecutable: Path,
        jarPath: Path,
        envFile: Path,
    ): String =
        """
        [Unit]
        Description=GitTally CI for ${repoRoot.fileName}
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

    fun envFileContent(): String =
        """
        # EnvironmentFile for the GitTally systemd service.
        # GitTally itself is configured via .gittally.yml and .git/gittally/.gittally.yml,
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
