#!/usr/bin/env bash
# Example: set up a Werkator instance that watches and builds Werkator itself.
# Run from inside a working checkout of the Werkator repository.
#
# Usage:
#   GIT_ACCOUNT=mi GIT_TOKEN=xxxx ./docs/examples/setup-werkator-selfhost.sh
#   (both empty is fine for a public origin — commit statuses just won't be published)
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-$HOME/werkator-selfhost}"
GIT_ACCOUNT="${GIT_ACCOUNT:-}"   # technical Gitea user for HTTPS fetch + status API
GIT_TOKEN="${GIT_TOKEN:-}"       # Gitea API token — stays in the uncommitted config file
SERVER_PORT="${SERVER_PORT:-18080}"

DEV_CHECKOUT=$(git rev-parse --show-toplevel)
ORIGIN_URL="${ORIGIN_URL:-$(git -C "$DEV_CHECKOUT" remote get-url origin)}"

# 1. Build the Werkator jar — the last Gradle run you ever start by hand.
(cd "$DEV_CHECKOUT" && ./gradlew --console=plain build)

# 2. Dedicated clone: builds run in worktrees under its .git/werkator/worktrees,
#    completely separate from your dev checkout.
mkdir -p "$INSTALL_DIR"
cp "$DEV_CHECKOUT/build/libs/werkator.jar" "$INSTALL_DIR/werkator.jar"
if [ ! -d "$INSTALL_DIR/repo/.git" ]; then
    git clone "$ORIGIN_URL" "$INSTALL_DIR/repo"
fi
cd "$INSTALL_DIR/repo"

# 3. Generate the config templates; existing files are kept. The clone already
#    carries the committed .werkator.yml, so this only creates the machine config
#    (.git/werkator/.werkator.yml).
java -jar "$INSTALL_DIR/werkator.jar" init

# 4. Machine-specific overrides and secrets (.git/werkator/ is never committed;
#    this file deep-merges over .werkator.yml and wins).
cat > .git/werkator/.werkator.yml <<EOF
git:
  account: "$GIT_ACCOUNT"
  token: "$GIT_TOKEN"
server:
  port: $SERVER_PORT
EOF
chmod 600 .git/werkator/.werkator.yml

# The committed .werkator.yml already builds Werkator itself:
#   buildCommand: ./gradlew --console=plain --no-daemon test
#   artifactDirs: [build/reports]

# 5. Optional kick-start: put the local ref one commit behind origin so the very
#    first poll triggers a build — otherwise Werkator waits for the next push.
#    Builds never move this ref or touch this checkout, so lagging is harmless.
git reset --hard --quiet HEAD~1 || true

# 6. Run it (Ctrl-C stops it cleanly). For a permanent setup, run
#    `java -jar "$INSTALL_DIR/werkator.jar" init --systemd` here instead and follow
#    docs/deployment.md — the generated unit points at this jar and repo.
echo "Werkator self-host: http://localhost:$SERVER_PORT/ — watching $ORIGIN_URL"
exec java -jar "$INSTALL_DIR/werkator.jar" server
