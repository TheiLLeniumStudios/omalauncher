#!/usr/bin/env bash
# Repacks Omarchy at the commit the app pins (ThemeStore.OMARCHY_REF) without what the
# launcher never uses but GitHub's tarball carries: the themes' intro videos (~700 MB)
# and lock-screen images. Writes omarchy-<ref>.tar.gz, laid out like GitHub's tarball
# (one omarchy-<ref>/ top directory), which CI publishes as the omarchy-<ref> release
# that OmarchyRelease downloads. Needs bsdtar (libarchive-tools).
set -euo pipefail
cd "$(dirname "$0")/.."
ref=$(sed -n 's/.*const val OMARCHY_REF = "\([0-9a-f]*\)".*/\1/p' app/src/main/kotlin/dev/omalauncher/theme/ThemeStore.kt)
[ -n "$ref" ] || { echo "OMARCHY_REF not found" >&2; exit 1; }
out="$PWD/omarchy-$ref.tar.gz"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "https://codeload.github.com/omacom/omarchy/tar.gz/$ref" |
  bsdtar -xzf - -C "$tmp" --exclude '*/backgrounds/intros/*' --exclude '*unlock.png'
top=$(ls "$tmp")
[ "$top" = "omarchy-$ref" ] || { echo "unexpected top directory: $top" >&2; exit 1; }
bsdtar -czf "$out" -C "$tmp" "$top"
ls -l "$out"
