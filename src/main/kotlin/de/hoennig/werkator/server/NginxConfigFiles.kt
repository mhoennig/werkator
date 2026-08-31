package de.hoennig.werkator.server

/**
 * Generates the nginx configuration for the managed proxy container, ported from
 * the legacy `artifact_nginx_write_config`/`artifact_nginx_write_ssl_options`.
 * Values are substituted verbatim — callers must validate the server name and
 * upstream host (see [NginxProxyManager.HOST_NAME_PATTERN]) so no nginx directives
 * can be injected.
 */
object NginxConfigFiles {
    /**
     * The `nginx.conf` content. Without [full] it is the init config for the
     * two-phase startup: HTTP only, serving the ACME webroot challenge and
     * redirecting everything else to HTTPS. With [full] an HTTPS server block
     * with the Let's Encrypt certificate and the proxy to Werkator is added.
     */
    fun nginxConf(
        serverName: String,
        upstreamHost: String,
        upstreamPort: Int,
        full: Boolean,
    ): String {
        val httpServer =
            """
            |    server {
            |        listen 80;
            |        server_name $serverName;
            |
            |        location /.well-known/acme-challenge/ {
            |            root /var/www/certbot;
            |        }
            |
            |        location / {
            |            return 301 https://${'$'}host${'$'}request_uri;
            |        }
            |    }
            """.trimMargin()
        val httpsServer =
            """
            |
            |    server {
            |        listen 443 ssl;
            |        server_name $serverName;
            |
            |        ssl_certificate /etc/letsencrypt/live/$serverName/fullchain.pem;
            |        ssl_certificate_key /etc/letsencrypt/live/$serverName/privkey.pem;
            |        include /etc/letsencrypt/options-ssl-nginx.conf;
            |
            |        location /.well-known/acme-challenge/ {
            |            root /var/www/certbot;
            |        }
            |
            |        location / {
            |            proxy_pass http://$upstreamHost:$upstreamPort;
            |            proxy_set_header Host ${'$'}host;
            |            proxy_set_header X-Real-IP ${'$'}remote_addr;
            |            proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;
            |            proxy_set_header X-Forwarded-Proto ${'$'}scheme;
            |            add_header Cache-Control "no-store, max-age=0" always;
            |            add_header Pragma "no-cache" always;
            |            add_header Expires "0" always;
            |        }
            |    }
            """.trimMargin()
        return "events {}\n\nhttp {\n" + httpServer + (if (full) httpsServer else "") + "\n}\n"
    }

    /** The certbot-recommended `options-ssl-nginx.conf`, verbatim from legacy. */
    const val SSL_OPTIONS: String =
        "ssl_session_cache shared:le_nginx_SSL:1m;\n" +
            "ssl_session_timeout 1440m;\n" +
            "ssl_protocols TLSv1.2 TLSv1.3;\n" +
            "ssl_prefer_server_ciphers off;\n" +
            "\n" +
            "ssl_ciphers \"ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:" +
            "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:" +
            "ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:" +
            "DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384\";\n" +
            "ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;\n"
}
