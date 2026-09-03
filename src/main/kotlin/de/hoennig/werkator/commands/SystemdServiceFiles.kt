package de.hoennig.werkator.commands

import java.nio.file.Path

/**
 * Generates the content of the systemd user unit and its `EnvironmentFile` for running
 * `werkator server` as a service — the shape of the legacy `generate_systemd_config`,
 * without the self-copy/self-update machinery (the unit points at the jar in place).
 */
object SystemdServiceFiles {
    const val ENV_FILE_NAME = "werkator.env"

    /** Host-global unit names of the nightly Docker cleanup — shared by all Werkator repositories on the host. */
    const val HTACCESS_NAME = "werkator.htaccess"

    /** Static page [maintenancePageContent] serves at, referenced by [htaccessContent]'s `ErrorDocument`s. */
    const val MAINTENANCE_PAGE_NAME = "werkator-maintenance.html"

    const val PRUNE_SERVICE_NAME = "werkator-docker-prune.service"
    const val PRUNE_TIMER_NAME = "werkator-docker-prune.timer"

    /** Per-repository unit name, because one Werkator instance serves exactly one repository. */
    fun unitName(repoRoot: Path): String = "werkator-${sanitize(repoRoot.fileName.toString())}.service"

    fun unitFileContent(
        repoRoot: Path,
        javaExecutable: Path,
        jarPath: Path,
        envFile: Path,
        memoryMax: String = "",
        tasksMax: String = "",
    ): String {
        val limits =
            listOfNotNull(
                "MemoryMax=$memoryMax".takeIf { memoryMax.isNotBlank() },
                "TasksMax=$tasksMax".takeIf { tasksMax.isNotBlank() },
            ).joinToString("\n")
        val limitsLine = if (limits.isEmpty()) "" else "\n$limits"
        return """
            [Unit]
            Description=Werkator CI for ${repoRoot.fileName}
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
            """.trimIndent().replace("\n\n[Install]", "$limitsLine\n\n[Install]") + "\n"
    }

    /**
     * Nightly Docker cleanup like the legacy `docker-prune.service`, but without `--volumes`:
     * the per-repository Gradle cache volumes must survive the cleanup.
     * Running containers and their images are never pruned, so an in-flight build is safe.
     */
    fun pruneServiceContent(): String =
        """
        [Unit]
        Description=Clean up unused Docker containers and images (Werkator)

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
        Description=Nightly Docker cleanup before the auto builds (Werkator)

        [Timer]
        OnCalendar=*-*-* 02:00:00
        Persistent=true

        [Install]
        WantedBy=timers.target
        """.trimIndent() + "\n"

    fun envFileContent(): String =
        """
        # EnvironmentFile for the Werkator systemd service.
        # Werkator itself is configured via .werkator.yml and .git/werkator/.werkator.yml,
        # not via environment variables; this file only tunes the JVM process.
        #JAVA_OPTS=-Xmx256m
        """.trimIndent() + "\n"

    /**
     * Apache reverse proxy for a Hostsharing managed webspace: the platform's
     * Apache terminates TLS for the domain and forwards everything to the
     * localhost port of the "eigener Serverdienst". Generated host integration
     * like the units — the wrapper copies it into the domain's docroot.
     * `ErrorDocument` maps a refused connection (Apache's 502/503/504 while the
     * service restarts) to the static [maintenancePageContent] instead of Apache's
     * default error page; the `RewriteCond` keeps that one file from being proxied
     * itself, since `ErrorDocument` serves it as a sub-request through the same rules.
     */
    fun htaccessContent(port: Int): String =
        """
        DirectoryIndex disabled
        ErrorDocument 502 /$MAINTENANCE_PAGE_NAME
        ErrorDocument 503 /$MAINTENANCE_PAGE_NAME
        ErrorDocument 504 /$MAINTENANCE_PAGE_NAME
        RewriteEngine On
        RewriteBase /
        RewriteCond %{REQUEST_URI} !^/${MAINTENANCE_PAGE_NAME}${'$'}
        RewriteRule .* http://127.0.0.1:$port%{REQUEST_URI} [proxy]
        """.trimIndent() + "\n"

    /**
     * Static fallback for [htaccessContent]'s `ErrorDocument`s: shown by Apache directly,
     * without involving Werkator, during the brief window where the service restarts and
     * nothing listens on its port yet (`instance-update`). Self-contained — no external
     * assets, since nothing would be there to serve them while Werkator itself is down.
     */
    fun maintenancePageContent(): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <title>Werkator — Maintenance</title>
        <style>
            body { font-family: sans-serif; background: #1e1e1e; color: #eee; display: flex;
                   align-items: center; justify-content: center; height: 100vh; margin: 0; }
            div { text-align: center; }
            h1 { font-size: 1.4rem; margin-bottom: .5rem; }
            p { color: #aaa; }
        </style>
        </head>
        <body>
        <div>
            <h1>Werkator is restarting</h1>
            <p>A deployment is in progress. Please retry in a few minutes.</p>
        </div>
        </body>
        </html>
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
