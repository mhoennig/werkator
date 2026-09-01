#!/usr/bin/env bash
#
# Build the Werkator bwrap build environment (rootfs) archive.
#
# The bwrap build runtime (step 17 / ADR 0007) runs each build inside a
# bubblewrap user namespace, chrooted into a *prepared* Debian root filesystem.
# That rootfs is NOT built on the target (the webspace has no root and no
# debootstrap), it is built once on any machine that can — most comfortably a
# machine with Docker — and distributed as an archive, e.g.
# `werkator-buildenv-trixie-java21.tar.zst`.
#
# This script builds exactly that archive: a debootstrap-minbase Debian release
# plus the packages Werkator itself needs to run `./gradlew build` inside the
# sandbox (JDK 21, git, ca-certificates, locales, curl/unzip for the wrapper).
# The whole build runs inside a throwaway Docker container, so no root is
# needed on the machine running this script.
#
# Why it works this way (each quirk learned the hard way):
#   - The whole job runs in ONE container whose stdin carries a base64-encoded
#     script (no file is bind-mounted for the script — a bind-mounted script
#     hit noexec/tmpfs trouble and vanished inside the container).
#   - The rootfs is built inside the container's own writable layer, NOT on a
#     bind-mounted host directory — debootstrap "Tried to extract package, but
#     tar failed" when its target sat on some bind-mounted/special filesystems.
#   - Only the final `tar --zstd` writes to stdout; every build step is
#     redirected to stderr, so the archive coming out of `docker run` is pure.
#
# Usage: build-bwrap-rootfs.sh [--release trixie] [--mirror URL] [--out path] [--pkgs-extra "PKG..."]
#   --release     Debian release/architecture tail, default "trixie"
#   --mirror      apt mirror for debootstrap, default http://deb.debian.org/debian
#   --out         output archive path, default ./werkator-buildenv-<release>.tar.zst
#   --pkgs-extra  additional apt packages on top of the base list, e.g.
#                 "golang-go nodejs npm" for Go and Node builds. Name the
#                 archive after its content (--out): the bwrap runtime keys the
#                 unpacked environment by the archive SOURCE PATH, so a changed
#                 content needs a changed name to take effect.
#

set -euo pipefail

die()  { echo "ERROR: $*" >&2; exit 1; }
warn() { echo "WARNING: $*" >&2; }

usage() {
    echo "usage: build-bwrap-rootfs.sh [--release trixie] [--mirror URL] [--out path]" >&2
    exit 1
}

# --------------------------------------------------------------- arguments --

release="trixie"
out=""
pkgs_extra=""
while [ $# -gt 0 ]; do
    case "$1" in
        --release) release="${2:?missing value for --release}"; shift 2 ;;
        --mirror)  mirror="${2:?missing value for --mirror}"; shift 2 ;;
        --out)     out="${2:?missing value for --out}"; shift 2 ;;
        --pkgs-extra) pkgs_extra="${2:?missing value for --pkgs-extra}"; shift 2 ;;
        -*) die "unknown option: $1" ;;
        *) usage ;;
    esac
done
mirror="${mirror:-http://deb.debian.org/debian}"
[ -n "$out" ] || out="$(pwd)/werkator-buildenv-${release}.tar.zst"

command -v docker >/dev/null 2>&1 || die "docker is required to build the rootfs"

# Rootfs content: Werkator's own build needs a JDK 21 toolchain (Gradle
# toolchain resolution), git, ca-certificates for HTTPS, locales for git, and
# curl/unzip/xz-utils/zstd for the Gradle wrapper and general build hygiene.
# Keep this list additive — project-specific tooling goes on top of this base.
PKGS="openjdk-21-jdk git ca-certificates locales procps file curl unzip xz-utils zstd"
[ -z "$pkgs_extra" ] || PKGS="$PKGS $pkgs_extra"

# The chroot step runs inside the freshly debootstrapped rootfs; passed into
# the container as base64 so no nested heredoc corrupts the piped script.
inner="$(printf '%s' '#!/bin/bash
set -euxo pipefail
mount -t proc none /proc
apt-get update -qq
apt-get install -y --no-install-recommends '"${PKGS}"'
apt-get clean
rm -f /etc/localtime
locale-gen en_US.UTF-8 de_DE.UTF-8 >/dev/null 2>&1 || true
update-locale LANG=en_US.UTF-8 >/dev/null 2>&1 || true
' | base64 -w0)"

# The outer script runs inside the Debian container as root. Build noise goes
# to stderr (fd 1 is saved on fd 3 and restored only for the final tar), so
# docker stdout is exactly the archive.
outer="$(printf '%s' '#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
exec 3>&1
exec 1>&2
apt-get update -qq
apt-get install -y --no-install-recommends debootstrap zstd ca-certificates
mkdir -p /b/rootfs
debootstrap --variant=minbase --components=main,contrib --include=apt,ca-certificates '"${release}"' /b/rootfs '"${mirror}"'
mount --bind /proc /b/rootfs/proc
mount --bind /sys /b/rootfs/sys
mount --bind /dev /b/rootfs/dev
echo '"${inner}"' | base64 -d > /b/rootfs/inner.sh
chmod +x /b/rootfs/inner.sh
chroot /b/rootfs /bin/bash /inner.sh
umount /b/rootfs/proc; umount /b/rootfs/sys; umount /b/rootfs/dev
exec 1>&3
tar --zstd --anchored --exclude=./proc --exclude=./sys --exclude=./dev -C /b/rootfs -cf - .
' | base64 -w0)"

echo "building ${release} rootfs (downloads packages, takes a while; log below)..."
echo "archive → $out"

# Stream the base64-encoded outer script into the container over stdin; the
# archive lands on stdout (redirected to $out), the build log on stderr.
docker run --rm -i --privileged debian:"${release}-slim" \
    bash -c 'base64 -d | bash' \
    <<<"$outer" >"$out"

echo
echo "OK: build environment written to $out"
echo "    Configure it as branches.<name>.bwrap.rootfs (a bare path or a URL)"
echo "    on the target Werkator instance to build in this environment."
