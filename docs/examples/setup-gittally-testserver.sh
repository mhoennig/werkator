#!/usr/bin/env bash
# Example: start a werkator test server watching a scratch repository with a fake build.
# This is the setup used for the manual UI/API smoke tests during development:
# a local bare origin (with a second branch for the Branches view), a slow fake
# build with live log output and a demo report artifact, and a fast poll
# interval — no Gitea, no credentials, no Docker.
#
# Usage:
#   ./docs/examples/setup-werkator-testserver.sh
#   BUILD_SECONDS=5 SERVER_PORT=18981 ./docs/examples/setup-werkator-testserver.sh
#
# While the server runs, trigger builds by pushing from the "work" clone:
#   git -C ~/werkator-testserver/work commit --allow-empty -m "trigger build" && git -C ~/werkator-testserver/work push
# A commit message containing "[fail]" makes the fake build fail;
# pushing a new branch exercises the new-origin-branch path.
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-$HOME/werkator-testserver}"
SERVER_PORT="${SERVER_PORT:-18980}"
export BUILD_SECONDS="${BUILD_SECONDS:-25}"   # inherited by the build process at runtime

DEV_CHECKOUT=$(git rev-parse --show-toplevel)

# 1. Build the werkator jar.
(cd "$DEV_CHECKOUT" && ./gradlew --console=plain build)
mkdir -p "$INSTALL_DIR"
cp "$DEV_CHECKOUT/build/libs/werkator.jar" "$INSTALL_DIR/werkator.jar"

# 2. Scratch bare origin plus a "work" clone for triggering builds by pushing.
if [ ! -d "$INSTALL_DIR/origin.git" ]; then
    git init --bare --quiet --initial-branch=main "$INSTALL_DIR/origin.git"
fi
if [ ! -d "$INSTALL_DIR/work/.git" ]; then
    git clone --quiet "$INSTALL_DIR/origin.git" "$INSTALL_DIR/work"
fi

# First run only: commit the fake build and the project config to the scratch repo.
if ! git -C "$INSTALL_DIR/work" rev-parse --quiet --verify HEAD >/dev/null; then
    cat > "$INSTALL_DIR/work/fake-build.sh" <<'EOF'
#!/usr/bin/env bash
# Fake build: visible progress for the live log, then a demo report artifact.
# werkator exports `branch`; BUILD_SECONDS is inherited from the server process.
set -euo pipefail
echo "fake build of branch ${branch:-unknown} at commit $(git rev-parse --short HEAD)"
if git log -1 --pretty=%s | grep -qF '[fail]'; then
    echo "commit message requests a failing build" >&2
    exit 1
fi
for i in $(seq 1 "${BUILD_SECONDS:-25}"); do
    echo "build step $i of ${BUILD_SECONDS:-25}"
    sleep 1
done
mkdir -p build/reports/demo
printf '<!DOCTYPE html><html><body><h1>Demo Report</h1><p>branch %s, commit %s</p></body></html>\n' \
    "${branch:-unknown}" "$(git rev-parse --short HEAD)" > build/reports/demo/index.html
echo "fake build done"
EOF
    chmod +x "$INSTALL_DIR/work/fake-build.sh"
    cat > "$INSTALL_DIR/work/.werkator.yml" <<'EOF'
watcher:
  pollInterval: 5s

branches:
  default:
    cleanCommand: rm -rf build
    buildCommand: ./fake-build.sh
    artifactDirs:
      - build/reports
EOF
    git -C "$INSTALL_DIR/work" add fake-build.sh .werkator.yml
    git -C "$INSTALL_DIR/work" commit --quiet -m "fake build setup"
    git -C "$INSTALL_DIR/work" commit --quiet --allow-empty -m "kick-start build"
    git -C "$INSTALL_DIR/work" push --quiet origin main
fi

# A second branch on origin, so the Branches view shows more than just main.
# Guarded separately so existing installs gain it on a re-run.
if ! git -C "$INSTALL_DIR/origin.git" show-ref --quiet refs/heads/feature/demo; then
    git -C "$INSTALL_DIR/work" switch --quiet -c feature/demo main
    git -C "$INSTALL_DIR/work" commit --quiet --allow-empty -m "demo branch for the branches view"
    git -C "$INSTALL_DIR/work" push --quiet -u origin feature/demo
    git -C "$INSTALL_DIR/work" switch --quiet main
fi

# 3. The watched clone; the server runs here. No `init` needed: the project config
#    is committed, and without gitea.baseUrl/git.token no statuses are published.
if [ ! -d "$INSTALL_DIR/repo/.git" ]; then
    git clone --quiet "$INSTALL_DIR/origin.git" "$INSTALL_DIR/repo"
fi
cd "$INSTALL_DIR/repo"
mkdir -p .git/werkator
cat > .git/werkator/.werkator.yml <<EOF
server:
  port: $SERVER_PORT
  bindAddress: 127.0.0.1
EOF

# 4. Kick-start: put the local ref one commit behind origin so the very first
#    poll triggers a build. Builds never move this ref, so lagging is harmless.
git reset --hard --quiet HEAD~1 || true

# 5. Run it (Ctrl-C stops it cleanly).
echo
echo "werkator test server: http://localhost:$SERVER_PORT/"
echo "Trigger a build:      git -C $INSTALL_DIR/work commit --allow-empty -m 'trigger build' && git -C $INSTALL_DIR/work push"
echo "Trigger a failure:    same with commit message 'trigger [fail]'"
echo
exec java -jar "$INSTALL_DIR/werkator.jar" server
