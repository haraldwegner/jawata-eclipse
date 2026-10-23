#!/bin/sh
# Installs the versioned commit-msg hook into this clone: run once after cloning (scripts/28k-e2e.sh checks it is installed and runs its test).
set -e
root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cp "$root/scripts/commit-msg" "$root/.git/hooks/commit-msg"
chmod +x "$root/.git/hooks/commit-msg"
