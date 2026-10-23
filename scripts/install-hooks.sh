#!/bin/sh
# Installs the versioned commit-msg hook into this clone: run once after cloning (the build checks it is installed).
set -e
root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cp "$root/scripts/commit-msg" "$root/.git/hooks/commit-msg"
chmod +x "$root/.git/hooks/commit-msg"
