# GitTally Deployment

This document describes how to run GitTally as a permanent service.
The recommended setup is a systemd user service behind an existing reverse proxy.
GitTally does not manage nginx or TLS certificates itself (unlike the legacy script); it relies on the host's existing web server and certbot.

## Prerequisites

- Linux with systemd.
- Java runtime (JRE 21).
- `git` CLI on the `PATH`.
- `docker` CLI on the `PATH`, only if any branch uses `docker.enabled` (see [configuration.md](configuration.md)).
- A checked-out working tree of the repository to watch, with a remote named `origin`.

## Jar Location Convention

Build the executable jar once:

```bash
./gradlew build
ls build/libs/gittally-*-SNAPSHOT.jar
```

Copy the jar to a stable path outside any watched repository, by convention `~/bin/gittally.jar`:

```bash
mkdir -p ~/bin
cp build/libs/gittally-0.1.0-SNAPSHOT.jar ~/bin/gittally.jar
```

The systemd unit generated below points at the jar that was used to run `init --systemd`.
So always run it via the stable path, not via `build/libs/`.

## Install the Service

Initialize GitTally in the repository to watch (see [bootstrapping.md](bootstrapping.md) for details):

```bash
cd /path/to/repo
java -jar ~/bin/gittally.jar init
# fill in git.account and git.token in .git/gittally/.gittally.yml
# review .gittally.yml
```

Generate the systemd user unit:

```bash
java -jar ~/bin/gittally.jar init --systemd
```

This writes `.git/gittally/gittally-<repo-name>.service` and `.git/gittally/gittally.env` and prints the install commands:

```bash
ln -sf /path/to/repo/.git/gittally/gittally-<repo-name>.service ~/.config/systemd/user/gittally-<repo-name>.service
systemctl --user daemon-reload
systemctl --user enable --now gittally-<repo-name>.service
```

The unit runs `java -jar ~/bin/gittally.jar server` with the repository as working directory and `Restart=always`.
The unit name contains the repository name, so several repositories can be served by one host, each with its own service and port.

User services stop at logout unless lingering is enabled once per user:

```bash
loginctl enable-linger "$USER"
```

## Operating the Service

```bash
systemctl --user status gittally-<repo-name>.service     # state and last log lines
journalctl --user -u gittally-<repo-name>.service -f     # follow the log
systemctl --user restart gittally-<repo-name>.service    # restart (e.g. after config changes)
systemctl --user stop gittally-<repo-name>.service       # stop
```

To update GitTally, replace `~/bin/gittally.jar` and restart the service.

## Environment File

`.git/gittally/gittally.env` is loaded by the unit as `EnvironmentFile`.
It only tunes the JVM process, e.g. `JAVA_OPTS=-Xmx256m`.
All GitTally configuration lives in the YAML files described in [configuration.md](configuration.md), not in environment variables.
`init --systemd` never overwrites an existing environment file.

## Reverse Proxy (nginx)

Bind GitTally to localhost and set the public URL in `.gittally.yml`:

```yaml
server:
  bindAddress: 127.0.0.1
  port: 18080
  publicBaseUrl: "https://ci.example.org/"
```

`publicBaseUrl` is used for all links posted to Gitea, so it must be the externally reachable URL.

Add a `server` block to the host's nginx:

```nginx
server {
    listen 443 ssl;
    server_name ci.example.org;

    ssl_certificate     /etc/letsencrypt/live/ci.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ci.example.org/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:18080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

Obtain and renew the certificate with the host's existing certbot, e.g.:

```bash
sudo certbot --nginx -d ci.example.org
```

This replaces the legacy script's managed nginx/Let's Encrypt Docker container, which was intentionally not ported (see [migration-from-legacy.md](migration-from-legacy.md)).
