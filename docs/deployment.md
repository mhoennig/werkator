# Werkator Deployment

This document describes how to run Werkator as a permanent service.
The recommended setup is a systemd user service behind an existing reverse proxy.
By default Werkator does not manage nginx or TLS certificates itself; it relies on the host's existing web server and certbot.
For hosts without one, an opt-in managed nginx/TLS container is available, see [Hosts Without a Reverse Proxy](#hosts-without-a-reverse-proxy-managed-nginxtls).

## Prerequisites

- Linux with systemd.
- Java runtime (JRE 21) — or none, when using the self-contained runtime bundle, see [Hosts Without a Java Runtime](#hosts-without-a-java-runtime-runtime-bundle).
- `git` CLI on the `PATH`.
- `docker` CLI on the `PATH`, only if any branch uses `docker.enabled` (see [configuration.md](configuration.md)).
- A checked-out working tree of the repository to watch, with a remote named `origin`.

## Jar Location Convention

Build the executable jar once:

```bash
./gradlew build
ls build/libs/werkator.jar
```

Copy the jar to a stable path outside any watched repository, by convention `~/bin/werkator.jar`:

```bash
mkdir -p ~/bin
cp build/libs/werkator.jar ~/bin/werkator.jar
```

The systemd unit generated below points at the jar that was used to run `init --systemd`.
So always run it via the stable path, not via `build/libs/`.

## Install the Service

Initialize Werkator in the repository to watch (see [bootstrapping.md](bootstrapping.md) for details):

```bash
cd /path/to/repo
java -jar ~/bin/werkator.jar init
# fill in git.account and git.token in .git/werkator/.werkator.yml
# review .werkator.yml
```

Generate the systemd user unit:

```bash
java -jar ~/bin/werkator.jar init --systemd
```

This writes `.git/werkator/werkator-<repo-name>.service`, `.git/werkator/werkator.env`, and the nightly Docker cleanup units (`werkator-docker-prune.service`/`.timer`), and prints the install commands:

```bash
ln -sf /path/to/repo/.git/werkator/werkator-<repo-name>.service ~/.config/systemd/user/werkator-<repo-name>.service
ln -sf /path/to/repo/.git/werkator/werkator-docker-prune.service ~/.config/systemd/user/werkator-docker-prune.service
ln -sf /path/to/repo/.git/werkator/werkator-docker-prune.timer ~/.config/systemd/user/werkator-docker-prune.timer
systemctl --user daemon-reload
systemctl --user enable --now werkator-<repo-name>.service
systemctl --user enable --now werkator-docker-prune.timer
```

The unit runs `java -jar ~/bin/werkator.jar server` with the repository as working directory and `Restart=always`.
The unit name contains the repository name, so several repositories can be served by one host, each with its own service and port.

### Nightly Docker Cleanup

The `werkator-docker-prune.timer` runs `docker system prune -af` every night at 02:00 (host time), before the usual auto-build slots.
It removes stopped containers, unused images, unused networks, and dangling build cache, so nightly builds start from freshly built images.
Unlike the legacy cleanup it does **not** prune volumes — the per-repository Gradle cache volumes survive.
The units are host-global (no repository name): with several Werkator instances on one host, every `init --systemd` generates the same files and the symlinks coincide.
On hosts without a `docker` CLI the service is skipped, not failed (`ExecCondition`).
`Persistent=true` catches up a missed run after downtime.

Expect a burst of builds right after the very first start: in a fresh clone every origin branch counts as new, so each branch with commits younger than `watcher.newBranchMaxAge` (default 5d) is built once — one build per branch, executed serially up to `builds.maxConcurrent`.
Lower `watcher.newBranchMaxAge` before the first start, or enable `requirePullRequest`, to limit the initial backlog.

User services stop at logout unless lingering is enabled once per user:

```bash
loginctl enable-linger "$USER"
```

## Serving Several Repositories

One instance serves a *set* of repositories (ADR 0009): one service, one port, one UI, one watcher loop, one control token.
Adding a repository is editing a registry entry — never a data migration, because everything repository-specific already lives inside the repository (`.git/werkator/`: machine config with secrets, build results, auto-build slots, worktrees).

1. **Clone it** on the host, next to the ones already served:

   ```bash
   git clone https://github.com/<owner>/<repo>.git ~/repos/<repo>
   ```

2. **Prepare it** like any watched repository — the machine config with the git credentials, and the sandbox policy if the host needs one:

   ```bash
   cd ~/repos/<repo> && java -jar ~/bin/werkator.jar init
   # fill in git.account and git.token in .git/werkator/.werkator.yml
   ```

   Credentials shared by every repository of the same forge can live once in the instance file's `defaults` block instead (`docs/configuration.md`); they merge below each repository's own layers, so a repository may still override them.
   The repository's own `.werkator.yml` — its builds — is committed and comes with the clone.

3. **Register it** in `~/.werkator.yml` of the user running the service:

   ```yaml
   repositories:
     - path: ~/repos/werkator
     - path: ~/repos/<repo>
       name: <short-name>      # optional; default is the directory basename
   ```

   The name is the route segment (`/repos/<name>/…`) and the UI's switcher entry, so it must be unique: a duplicate aborts the start naming this file, and so does an entry that is no git repository.
   A repository whose configuration Werkator must not read (a version violation) is skipped with an error — the others keep building.

   Steps 1 and 2 are mechanical and can be done from the workstation:

   ```bash
   tools/remote --env-file .env.<instance> werkator repo-add https://github.com/<owner>/<repo>.git [<name>]
   ```

   It clones the repository next to the ones already served, runs `init` in it, and **prints** the registry entry.
   It does not write `~/.werkator.yml`: that file is the instance's own — port, global concurrency, possibly shared credentials — and a script editing it in place would rewrite the operator's configuration behind their back.
   Cloning and initialising is mechanical; registering is a decision.

4. **Restart** the service; startup recovery re-enqueues what was in flight:

   ```bash
   systemctl --user restart werkator-<repo-name>.service
   ```

The service unit keeps the name it was installed with — it is the instance's unit, not one repository's.
With one registered repository every URL stays what it was; with several, the pages and the API carry the repository (`/repos/<name>/…`, `/api/repos/<name>/…`), the unscoped paths keep meaning the repository the instance was started in, and the navigation shows a switcher.
The watcher polls every repository in its own guard: an unreachable origin is that repository's report in the health banner, and the others are polled regardless.
`executor.maxConcurrent` is the global cap across all of them, and builds take slots in enqueue order (FIFO).

## Operating the Service

```bash
systemctl --user status werkator-<repo-name>.service     # state and last log lines
journalctl --user -u werkator-<repo-name>.service -f     # follow the log
systemctl --user restart werkator-<repo-name>.service    # restart (e.g. after config changes)
systemctl --user stop werkator-<repo-name>.service       # stop
```

## Updating an Existing Installation

A restart is safe at any time: an in-flight build is recorded as `INTERRUPTED` and re-enqueued by the startup recovery.
Waiting for an idle queue is still nicer, because the interrupted build starts over from scratch.

```bash
curl -s http://127.0.0.1:18080/api/builds/current      # ideally [] — nothing running
```

With a jar installation:

```bash
./gradlew build                                        # on the dev machine
scp build/libs/werkator.jar <user>@<host>:~/bin/werkator.jar.new
ssh <user>@<host>
  systemctl --user stop werkator-<repo-name>.service
  mv ~/bin/werkator.jar ~/bin/werkator.jar.bak         # rollback copy
  mv ~/bin/werkator.jar.new ~/bin/werkator.jar
  systemctl --user start werkator-<repo-name>.service
  systemctl --user is-active werkator-<repo-name>.service
```

With a runtime bundle (hosts without Java, see below):

```bash
./gradlew runtimeBundle                                # on the dev machine
scp build/distributions/werkator-runtime-linux-x64.tar.gz <user>@<host>:/tmp/werkator-new.tar.gz
ssh <user>@<host>
  systemctl --user stop werkator-<repo-name>.service
  mv ~/opt/werkator ~/opt/werkator.bak                 # rollback copy
  tar xzf /tmp/werkator-new.tar.gz -C /tmp/ && mv /tmp/werkator ~/opt/werkator
  ~/opt/werkator/bin/werkator --version                # expected: the new version
  systemctl --user start werkator-<repo-name>.service
  systemctl --user is-active werkator-<repo-name>.service
  rm -f /tmp/werkator-new.tar.gz
```

The tarball unpacks to a `werkator/` directory, so it must not be extracted over `~/opt` directly — unpack it in `/tmp` and move it into place, as above.
Rollback is the reverse: stop, remove the new directory (or jar), move `.bak` back, start.

Then check `https://<public-url>/` for the new version in the footer, and `journalctl --user -u werkator-<repo-name>.service -n 50` for a clean start.
Config file changes are not needed for an update; new keys take their defaults.

## Control Token

Viewing is public by design: build states, logs and artifacts are readable without any login, so they can be linked from Gitea, chats or tickets.
That is safe as long as the builds themselves handle no real secrets — Werkator has no per-endpoint gating, so an installation whose build output could contain credentials must stay off the public internet (reverse proxy with access control, or `server.bindAddress: 127.0.0.1`).
Only the three mutating actions — restart, cancel, delete — require the control token from `.git/werkator/control-token`, a random secret the server generates on first start (mode `0600`; delete the file to rotate it).

The token is never embedded in a page.
The first time you press one of the control buttons, the browser asks for it once and keeps it in `localStorage` for that browser; a rejected token is dropped and asked for again.
Read it on the host:

```bash
cat ~/<repo>/.git/werkator/control-token
```

For scripts, pass it as a header — it is not accepted as a query parameter, because URLs end up in access logs and browser history:

```bash
curl -X POST -H "X-werkator-Token: $(cat .git/werkator/control-token)" \
  "https://ci.example.org/api/builds/restart?branch=main"
```

## Environment File

`.git/werkator/werkator.env` is loaded by the unit as `EnvironmentFile`.
It only tunes the JVM process, e.g. `JAVA_OPTS=-Xmx256m`.
All Werkator configuration lives in the YAML files described in [configuration.md](configuration.md), not in environment variables.
`init --systemd` never overwrites an existing environment file.

## Reverse Proxy (nginx)

Bind Werkator to localhost — the default since v0.9.9 — and set the public URL in `.werkator.yml`:

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

This replaces the legacy script's managed nginx/Let's Encrypt Docker container for hosts that have their own web server.

## Hosts Without a Java Runtime (Runtime Bundle)

Some hosts provide git and Docker but no Java runtime and no way to install one, e.g. Hostsharing container servers.
For these, Werkator ships as a self-contained runtime bundle: a jlink-trimmed JRE, `werkator.jar`, and a launcher script in one tarball (ADR 0006).

Build the bundle on a Linux x86_64 machine whose glibc is not newer than the target's:

```bash
./gradlew runtimeBundle
ls build/distributions/werkator-runtime-linux-x64.tar.gz
```

Copy and unpack it on the target host, by convention to `~/opt/werkator`:

```bash
scp build/distributions/werkator-runtime-linux-x64.tar.gz user@host:/tmp/
ssh user@host 'mkdir -p ~/opt && tar -xzf /tmp/werkator-runtime-linux-x64.tar.gz -C ~/opt'
```

Then use `~/opt/werkator/bin/werkator` wherever this document says `java -jar ~/bin/werkator.jar`:

```bash
cd /path/to/repo
~/opt/werkator/bin/werkator init
~/opt/werkator/bin/werkator init --systemd
```

`init --systemd` detects the bundle automatically: the generated unit's `ExecStart` points at the bundle's `jre/bin/java` and `lib/werkator.jar`, so the install commands printed by `init --systemd` work unchanged.
`JAVA_OPTS` from the environment file applies as usual.

To update Werkator, stop the service, unpack the new bundle over `~/opt/werkator`, and restart the service.

## Hosts Without a Reverse Proxy (Managed nginx/TLS)

Some hosts provide Docker but no root access and no host web server, e.g. Hostsharing managed container environments.
For these, Werkator can manage its own nginx+certbot Docker container (ADR 0005).
This is opt-in; where a host web server exists, prefer the reverse-proxy setup above.

Enable it in the server section of the configuration:

```yaml
server:
  port: 18080
  nginx:
    enabled: true
    serverName: ci.example.org
    httpPort: 8080
    httpsPort: 8443
    letsencryptEmail: admin@example.org
```

On server start, Werkator writes the nginx configuration, starts a labelled nginx container publishing `httpPort` and `httpsPort`, obtains a Let's Encrypt certificate via a certbot container (webroot mode), and restarts nginx with the full HTTPS configuration.
A renewal check runs daily; certificates and nginx state persist in `server.nginx.stateDir` across restarts.
On shutdown the container is removed.
All nginx and certificate failures are non-fatal warnings — the plain HTTP server keeps running without the proxy.

`serverName` must be a public DNS name pointing at the host, reachable from the internet on port 80/443 (directly or via a port forward to `httpPort`/`httpsPort`), otherwise the ACME challenge fails.
The nginx container cannot reach `localhost` of the host, so the proxy upstream defaults to `serverName`; set `server.nginx.upstreamHost` if the host is reachable under a different name from inside containers.
With the managed nginx, set `server.bindAddress: 0.0.0.0` explicitly (or an address reachable from the Docker network) — the default `127.0.0.1` makes Werkator unreachable for the proxy container.
See [configuration.md](configuration.md) for all `server.nginx.*` keys.

## Hostsharing Managed Webspace

The third deployment variant (plan step 21, verified live on a real webspace): no root, no Docker daemon, no own reverse proxy.
Werkator runs as a systemd *user* service on the assigned localhost port ("eigener Serverdienst"), the platform's managed Apache terminates TLS and proxies via `.htaccess`, and builds run in the bubblewrap sandbox executed by the [werkdock](https://git.javagil.de/mi/werkdock) CLI (ADR 0008, step 21 session C).

Werkator is never built on the webspace: the runtime bundle and the werkdock binary are built locally and uploaded (ADR 0006).
All steps are driven by `tools/remote`; commands name their role — `instance-*` manages the installed Werkator, `repo-*` the repository it watches.
Each instance is a pair of files (step 23): a transport env file selected with `--env-file` (default `.env`), and a YAML fragment in the configuration schema, named by its `WERKATOR_INIT_CONFIG` key and installed remotely via `werkator init --apply` — e.g. `.env.mih34` + `.env.mih34.yml`, both gitignored.
The fragment carries the Werkator configuration (`server.port`, `publicBaseUrl`, systemd limits, `builds.default.bwrap.*`); the env file only says where and how to reach the host.

```bash
tools/remote --env-file .env.mih34 werkator check-prerequisites  # uploads werkdock, runs its doctor
tools/remote --env-file .env.mih34 werkator instance-install     # upload + unpack the runtime bundle and werkdock
tools/remote --env-file .env.mih34 werkator repo-init            # clone the watched repo, rootfs archive, init --apply
tools/remote --env-file .env.mih34 werkator instance-start       # init --apply --systemd, place .htaccess, enable the unit
tools/remote --env-file .env.mih34 port-forward start            # browser tunnel while no public domain is set up
```

Layout on the host: the watched repository at `$WERKATOR_PATH/werkator/`, the unpacked runtime at `$WERKATOR_PATH/.werkator/werkator/`, the werkdock binary at `$WERKATOR_PATH/.werkator/bin/werkdock`.
The rootfs archive is loaded once per source into werkdock's image store (`~/.werkdock`), shared by every repository of the user.
Fill `git.account`/`git.token` in the machine config when the origin is private, and make the user's services survive logout with `loginctl enable-linger`.

Updates are one command, refused while a build runs (`FORCE=1` overrides):

```bash
tools/remote --env-file .env.mih34 werkator instance-update
```

The previous runtime stays as `.werkator/werkator.prev` for one deployment as the rollback asset.
