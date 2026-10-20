#!/usr/bin/env bash
# Puts the Miniwall spec version pinned in SPEC_VERSION into .spec/ (override the folder with SPEC_DIR).
# The tests read fixtures, golden frames, vectors and the mock-API screens from there.
set -euo pipefail
cd "$(dirname "$0")/.."
dir="${SPEC_DIR:-.spec}"
version="$(tr -d '[:space:]' < SPEC_VERSION)"
if [ ! -d "$dir/.git" ]; then
  git clone --quiet https://github.com/miniwall-app/miniwall-spec "$dir"
fi
git -C "$dir" fetch --quiet origin
git -C "$dir" checkout --quiet "$version"
echo "spec $version -> $dir"
