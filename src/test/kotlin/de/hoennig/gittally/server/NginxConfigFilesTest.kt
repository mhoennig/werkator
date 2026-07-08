package de.hoennig.gittally.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class NginxConfigFilesTest : FunSpec() {
    init {
        test("init config serves the ACME challenge and redirects to HTTPS, without a TLS server") {
            val conf = NginxConfigFiles.nginxConf("ci.example.org", "ci.example.org", 18080, full = false)

            conf shouldContain "listen 80;"
            conf shouldContain "server_name ci.example.org;"
            conf shouldContain "location /.well-known/acme-challenge/"
            conf shouldContain "root /var/www/certbot;"
            conf shouldContain "return 301 https://\$host\$request_uri;"
            conf shouldNotContain "listen 443"
            conf shouldNotContain "proxy_pass"
            conf shouldNotContain "ssl_certificate"
        }

        test("full config adds the TLS server with certificate paths and the proxy") {
            val conf = NginxConfigFiles.nginxConf("ci.example.org", "upstream.example.org", 18081, full = true)

            conf shouldContain "listen 80;"
            conf shouldContain "listen 443 ssl;"
            conf shouldContain "ssl_certificate /etc/letsencrypt/live/ci.example.org/fullchain.pem;"
            conf shouldContain "ssl_certificate_key /etc/letsencrypt/live/ci.example.org/privkey.pem;"
            conf shouldContain "include /etc/letsencrypt/options-ssl-nginx.conf;"
            conf shouldContain "proxy_pass http://upstream.example.org:18081;"
            conf shouldContain "proxy_set_header Host \$host;"
            conf shouldContain "proxy_set_header X-Forwarded-Proto \$scheme;"
            conf shouldContain "add_header Cache-Control \"no-store, max-age=0\" always;"
        }

        test("ssl options reference the mounted dhparams and modern protocols") {
            NginxConfigFiles.SSL_OPTIONS shouldContain "ssl_protocols TLSv1.2 TLSv1.3;"
            NginxConfigFiles.SSL_OPTIONS shouldContain "ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;"
        }
    }
}
