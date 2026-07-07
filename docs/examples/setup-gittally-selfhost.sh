#!/usr/bin/env bash
# Example: set up a GitTally instance that watches and builds GitTally itself.
# Run from inside a working checkout of the gittally repository.
#
# Usage:
#   GIT_ACCOUNT=mi GIT_TOKEN=xxxx ./docs/examples/setup-gittally-selfhost.sh
#   (both empty is fine for a public origin — commit statuses just won't be published)
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-$HOME/gittally-selfhost}"
GIT_ACCOUNT="${GIT_ACCOUNT:-}"   # technical Gitea user for HTTPS fetch + status API
GIT_TOKEN="${GIT_TOKEN:-}"       # Gitea API token — stays in the uncommitted config file
SERVER_PORT="${SERVER_PORT:-18080}"

DEV_CHECKOUT=$(git rev-parse --show-toplevel)
ORIGIN_URL="${ORIGIN_URL:-$(git -C "$DEV_CHECKOUT" remote get-url origin)}"

# 1. Build the GitTally jar — the last Gradle run you ever start by hand.
(cd "$DEV_CHECKOUT" && ./gradlew --console=plain build)

# 2. Dedicated clone: builds run in worktrees under its .git/gittally/worktrees,
#    completely separate from your dev checkout.
mkdir -p "$INSTALL_DIR"
JAR=$(ls "$DEV_CHECKOUT"/build/libs/gittally-*.jar | grep -v -- '-plain' | head -1)
cp "$JAR" "$INSTALL_DIR/gittally.jar"
if [ ! -d "$INSTALL_DIR/repo/.git" ]; then
    git clone "$ORIGIN_URL" "$INSTALL_DIR/repo"
fi
cd "$INSTALL_DIR/repo"

# 3. Generate the config templates; existing files are kept, Gitea URL/owner/repo
#    are detected from the origin URL.
java -jar "$INSTALL_DIR/gittally.jar" init

# 4. Machine-specific overrides and secrets (.git/gittally/ is never committed;
#    this file deep-merges over .gittally.yml and wins).
cat > .git/gittally/.gittally.yml <<EOF
git:
  account: "$GIT_ACCOUNT"
  token: "$GIT_TOKEN"
server:
  port: $SERVER_PORT
EOF
chmod 600 .git/gittally/.gittally.yml

# The generated defaults already build GitTally itself:
#   buildCommand: ./gradlew --console=plain --no-daemon test
#   artifactDirs: [build/reports]

# 5. Optional kick-start: put the local ref one commit behind origin so the very
#    first poll triggers a build — otherwise GitTally waits for the next push.
#    Builds never move this ref or touch this checkout, so lagging is harmless.
git reset --hard --quiet HEAD~1 || true

# 6. Run it (Ctrl-C stops it cleanly; wrap this in a systemd unit for a permanent setup).
echo "GitTally self-host: http://localhost:$SERVER_PORT/ — watching $ORIGIN_URL"
exec java -jar "$INSTALL_DIR/gittally.jar" server
