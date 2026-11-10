#!/usr/bin/env bash
# scripts/28k-dogfood.sh removes its clone of jawata-mcp when a run ends, and keeps the report and the output (Sprint 28h D54 (i)).
#
# Runs the script in a temporary copy of the files it needs (never in this working tree), with stubs for the things that are heavy: the
# Java download is a tiny archive holding an `eclipse` that exits 0, `xvfb-run` runs its command, and `mvn` prints the one-test summary and writes
# the report. The clone is made by the real git from a one-commit repository tagged with the engine version. Nothing is downloaded and nothing of
# this repository's .work is touched.
#
# The last block runs a copy of the script with the removal taken out and requires the clone check to go red: a check that cannot fail on a no-op
# is not a check.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
passed=0 failed=0
ok()   { echo "  ok    $*"; passed=$((passed + 1)); }
fail() { echo "  FAIL  $*"; failed=$((failed + 1)); }
for need in git tar; do command -v "$need" > /dev/null || { echo "$need is not installed"; exit 2; }; done

# One run of a script file against a fresh stub tree; prints the tree's name. $1: the script to run.
run_in_a_tree() {
    local script="$1" tree
    tree="$(mktemp -d -p "$tmp")"
    mkdir -p "$tree/scripts" "$tree/.work/dl-java" "$tree/repository/target/repository" "$tree/stubs" "$tree/source" "$tree/dist/bundles" "$tree/archive/eclipse"
    cp "$script" "$tree/scripts/28k-dogfood.sh"
    echo "9.9.9" > "$tree/engine.version"
    : > "$tree/repository/target/repository/content.jar"
    printf '#!/bin/sh\nexit 0\n' > "$tree/archive/eclipse/eclipse"
    chmod +x "$tree/archive/eclipse/eclipse"
    tar -czf "$tree/.work/dl-java/eclipse-java-2026-09-R-linux-gtk-x86_64.tar.gz" -C "$tree/archive" eclipse
    printf '#!/bin/sh\nif [ "$1" = "-a" ]; then shift; fi\nexec "$@"\n' > "$tree/stubs/xvfb-run"
    printf '#!/bin/sh\necho "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"\necho "jawata-dogfood: stub line"\nprintf "x\\n" > "$JAWATA_DOGFOOD_OUT/dogfood-report.tsv"\nexit 0\n' > "$tree/stubs/mvn"
    chmod +x "$tree/stubs/xvfb-run" "$tree/stubs/mvn"
    (cd "$tree/source" && git init -q . && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m one && git tag v9.9.9)
    PATH="$tree/stubs:$PATH" JAWATA_MCP="$tree/source" JAWATA_DOGFOOD_DIST="$tree/dist" bash "$tree/scripts/28k-dogfood.sh" > "$tree/run.out" 2>&1
    echo "$? $tree"
}

check_a_run() {  # $1: the tree, $2: the exit code, $3: label
    local tree="$1" rc="$2" label="$3"
    [ "$rc" = 0 ] && ok "$label: the run passes" || { fail "$label: the run exited $rc: $(tail -3 "$tree/run.out" | tr '\n' ' ')"; return; }
    [ -f "$tree/.work/dogfood/dogfood-report.tsv" ] && ok "$label: the report is kept" || fail "$label: the report is gone"
    [ -f "$tree/.work/dogfood/dogfood.out" ] && ok "$label: the output is kept" || fail "$label: the output is gone"
}

read -r rc tree < <(run_in_a_tree "$here/scripts/28k-dogfood.sh")
check_a_run "$tree" "$rc" "the script"
[ ! -e "$tree/.work/dogfood/jawata-mcp" ] && ok "the script: the clone is removed" || fail "the script: the clone is still there"

# The planted break: the same script without the removal line.
grep -v 'rm -rf "\$run/jawata-mcp"' "$here/scripts/28k-dogfood.sh" > "$tmp/without-removal.sh"
read -r rc tree < <(run_in_a_tree "$tmp/without-removal.sh")
check_a_run "$tree" "$rc" "without the removal"
[ -e "$tree/.work/dogfood/jawata-mcp" ] && ok "without the removal: the clone check sees the clone, so it can go red" || fail "without the removal: the clone is gone anyway, so the check proves nothing"

echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
