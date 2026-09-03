#!/usr/bin/env bash
#
# Push the current main branch to the GitHub mirror.
#
# Werkator's canonical repository lives on git.javagil.de (Gitea, remote
# "origin"); GitHub stays around as a public read-only mirror (remote
# "github"). This script keeps that mirror's main branch in sync — nothing
# else: no other branches, no force-push, no tags. Run it after pushing main
# to origin, or set it up as a post-push hook / cron job if that becomes
# annoying to remember.
#
# Usage: tools/push-github-mirror.sh

set -euo pipefail

cd "$(dirname "$0")/.."

git fetch origin main
git push github refs/remotes/origin/main:refs/heads/main
