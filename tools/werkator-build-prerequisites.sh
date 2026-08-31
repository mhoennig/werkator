#!/usr/bin/env bash
#
# Verify the bwrap (bubblewrap) build precondition on a target host before
# running Werkator's bwrap build runtime there (step 17 / ADR 0007).
#
# The whole "build Werkator inside bubblewrap on a Managed Webspace" approach
# hinges on one hard precondition: unprivileged user namespaces with a uid-0
# mapping and read-only root binds must work. This script runs the exact
# command line recorded in docs/plan/17-bwrap-build-runtime.md, checks the
# expected signals, and additionally verifies the disk/quota situation:
# a bwrap build unpacks the rootfs (a zstd archive expands to several GiB)
# plus a Gradle distribution and per-branch caches, so the host needs both
# raw free space and enough group-quota headroom.
#
# Run this ON the target host (the webspace), no root needed.
#
# Optional: the rootfs archive to size the disk/quota check against, e.g.
#   werkator-build-prerequisites.sh /path/to/werkator-buildenv-trixie.tar.zst
# When omitted, the check runs against a conservative default footprint.
#
# Usage: werkator-build-prerequisites.sh [TARGET_DIR] [ROOTFS_ARCHIVE]
#
# TARGET_DIR is the directory the build workspace will live in (default: $HOME).
# The check verifies it sits on the home filesystem and has enough free space.
# ROOTFS_ARCHIVE, when given, is the rootfs archive that will be used there.
#
# Output is one PASS/FAIL line per check plus a final RESULT line, e.g.:
#   PASS: bwrap version: bubblewrap 0.8.0
#   PASS: build runs as root inside the namespace (uid 0)
#   PASS: uid_map maps root back to the unprivileged user (uid 120957)
#   PASS: read-only root bind is enforced
#   PASS: at least 5 GiB free space on the build working filesystem
#   FAIL: group quota headroom below the 5 GiB build footprint ...
#   RESULT: FAIL (4/5) — Werkator bubblewrap builds are not usable on this host.
#

set -euo pipefail

target_dir_arg="${1:-}"
rootfs_arg="${2:-}"

die()  { echo "ERROR: $*" >&2; exit 1; }

# Disk footprint a bwrap build needs headroom for, in 1K blocks: unpacked
# rootfs (zstd expands roughly 3-4x), Gradle distribution + per-branch cache,
# build output and artifacts. ~5 GiB.
MIN_FREE_BLOCKS=$((5 * 1024 * 1024))

# Reference filesystem: the one the invoking user's home directory lives on.
# Builds (repo clone, buildenv, caches) must run there — other mounts, such as
# a slow mass-storage volume, are rejected.
HOME_FS="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $1}')"

pass=0
fail=0
result() {  # result PASS|FAIL "message"
    echo "$1: $2"
    if [ "$1" = "PASS" ]; then pass=$((pass+1)); else fail=$((fail+1)); fi
}

command -v bwrap >/dev/null 2>&1 || die "bwrap is not installed on this host"

output="$(bwrap --unshare-user --unshare-pid --die-with-parent --uid 0 --gid 0 \
      --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp \
      sh -c 'id -u && cat /proc/self/uid_map && (touch /usr/ro-test 2>&1 || true)' 2>&1)" ||
    die "bwrap invocation failed (no user namespace support?): $output"

# Signal 0: bwrap itself is usable (version as a visible marker).
result PASS "bwrap version: $(bwrap --version 2>&1)"

# Signal 1: runs as root (uid 0) inside the namespace.
first="$(printf '%s\n' "$output" | sed -n '1p')"
if [ "$first" = "0" ]; then
    result PASS "build runs as root inside the namespace (uid 0)"
else
    result FAIL "expected uid 0 inside the namespace, got: $first"
fi

# Signal 2: uid_map maps root to the invoking unprivileged user.
uid_line="$(printf '%s\n' "$output" | sed -n '2p')"
self_uid="$(id -u)"
if printf '%s\n' "$uid_line" | grep -E "^[[:space:]]*0[[:space:]]+${self_uid}[[:space:]]+1" >/dev/null; then
    result PASS "uid_map maps root back to the unprivileged user (uid $self_uid)"
else
    result FAIL "expected uid_map '0 $self_uid 1', got: $uid_line"
fi

# Signal 3: the read-only root bind is enforced (a write to /usr fails).
if printf '%s\n' "$output" | grep -qi "read-only file system"; then
    result PASS "read-only root bind is enforced"
else
    result FAIL "the read-only root bind did not reject a write to /usr"
fi

# --- Disk / quota checks ------------------------------------------------

target_dir="${target_dir_arg:-$HOME}"
target_dir="$(realpath -m "$target_dir")"
min_gib=$((MIN_FREE_BLOCKS / 1024 / 1024))

if [ -n "$rootfs_arg" ] && [ ! -f "$rootfs_arg" ]; then
    echo "WARNING: rootfs archive not found: $rootfs_arg (continuing without it)"
fi

target_fs="$(df -Pk "$target_dir" 2>/dev/null | awk 'NR==2 {print $1}')"
df_output="$(df -Pk "$target_dir" 2>/dev/null | awk 'NR==2 {print int($4) " " $6}')"
if [ -n "$df_output" ]; then
    avail_k="${df_output%% *}"
    mount="${df_output##* }"
    if [ -n "$HOME_FS" ] && [ "$target_fs" != "$HOME_FS" ]; then
        # An explicitly chosen foreign filesystem is allowed (e.g. for testing)
        # but flagged: builds there will be slow.
        echo "WARNING: target dir is on $target_fs (mounted at $mount), not the home filesystem ($HOME_FS) — builds will run on slower storage"
    fi
    if [ "${avail_k:-0}" -lt "$MIN_FREE_BLOCKS" ]; then
        result FAIL "less than ${min_gib} GiB free space on the build working filesystem ($mount)"
    else
        result PASS "at least ${min_gib} GiB free space on the build working filesystem ($mount, device $target_fs)"
    fi
else
    echo "WARNING: could not measure free space on $target_dir — only the quota check below applies"
fi

if quota_output="$(quota -g 2>/dev/null)" && [ -n "$quota_output" ]; then
    quota_ok=1
    quota_seen=0
    detail=""
    while read -r fs blocks quota_limit; do
        quota_seen=1
        # Only the quota of the target filesystem counts — other volumes may
        # legitimately be full or unquota'd without affecting the build.
        if [ -n "$target_fs" ] && [ "$(basename "$fs")" != "$(basename "$target_fs")" ] && [ "$fs" != "$target_fs" ]; then
            continue
        fi
        headroom=$((quota_limit - blocks))
        if [ "$headroom" -lt "$MIN_FREE_BLOCKS" ]; then
            quota_ok=0
            detail+=" $(basename "$fs"): $(awk -v b="$headroom" 'BEGIN{printf "%.1f", b/1024/1024}') GiB free of quota;"
        fi
    done < <(printf '%s\n' "$quota_output" | awk '
        NF==1 && $1 ~ /^\// { pending_fs=$1; next }
        $1 ~ /^\// && $2 ~ /^[0-9]+$/ { print $1, $2, $4; pending_fs=""; next }
        $1 ~ /^[0-9]+[*]?/ && pending_fs != "" { gsub(/\*/, "", $1); print pending_fs, $1, $3; pending_fs="" }')
    if [ "$quota_seen" -eq 0 ]; then
        echo "WARNING: quota tooling present but no group quota lines could be parsed — only free space was checked"
    elif [ "$quota_ok" -eq 1 ]; then
        result PASS "group quota headroom covers the ${min_gib} GiB build footprint"
    else
        result FAIL "group quota headroom below the ${min_gib} GiB build footprint (rootfs + Gradle cache); raise the quota before building.$detail"
    fi
else
    echo "WARNING: no readable group quota tooling on this host — only free space was checked"
fi

total=$((pass + fail))
echo
if [ "$fail" -eq 0 ]; then
    echo "RESULT: PASS ($pass/$total) — Werkator bubblewrap builds are usable on this host."
    echo "Next: install the Werkator instance with: tools/remote werkator install ${WERKATOR_SSH_TARGET:-<user>@<host>} '$target_dir'"
    exit 0
else
    echo "RESULT: FAIL ($pass/$total) — Werkator bubblewrap builds are not usable on this host."
    exit 1
fi
