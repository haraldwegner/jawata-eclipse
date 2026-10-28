#!/usr/bin/env bash
# Plan Stage 24 (spec D11): the plug-in on this operating system, with the result written to docs/os-check-<os>.md.
#
# Linux: runs scripts/28k-e2e.sh, which already does what D11 asks of the agent's Linux run: it installs the built update site into
# clean Eclipse 4.41 Java and RCP downloads, starts each in a virtual screen, and runs the PDE tests INSIDE that installation (the chat
# in the middle and in a side panel, the last conversation, the preference page, the terminal, the Markdown preview). This script adds
# nothing to that; it runs it once and records what it said, with the machine and the desktop it ran on.
#
# macOS and Windows are run by Harald (plan Stage 24). They need the macOS and Windows Eclipse archives and their own launchers, which
# scripts/28k-e2e.sh does not have; this script says so and stops with exit code 3 instead of pretending.
#
# Not part of this run, each with its home in the plan: restart Eclipse and see the chat where it was (Stage 23's open gate), the
# callouts for D14's fixture and the commit draft with HEAD unchanged (waiting for the signed Amendment 1, stages 13b and 22b), R1 and a
# chat turn that calls a jawata tool in the real chat (21.15).
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
os="$(uname -s)"

case "$os" in
    Linux) name=linux ;;
    Darwin)
        echo "os-check on macOS is run by Harald and is not built yet: scripts/28k-e2e.sh downloads the Linux archives and starts Eclipse with xvfb-run."
        echo "It needs the macOS archive for this CPU, its launcher inside Eclipse.app, and no virtual screen. Plan Stage 24 owns it."
        exit 3 ;;
    *)
        echo "os-check: $os is not Linux or macOS; on Windows the run is scripts/os-check.ps1, run by Harald, which is not built yet (plan Stage 24)."
        exit 3 ;;
esac

out="$here/docs/os-check-$name.md"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
mkdir -p "$here/docs"

started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
bash "$here/scripts/28k-e2e.sh" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}

passed="$(grep -c '^  ok ' "$log")"
failed="$(grep -c '^  FAIL ' "$log")"
tests="$(grep -E '^Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$log" | tail -1)"
desktop="${XDG_SESSION_TYPE:-none (virtual screen only)}"
{
    echo "# os-check on $name"
    echo
    echo "- started: $started (UTC)"
    echo "- machine: $(uname -sr), $(uname -m); the run used Xvfb, a virtual screen; the desktop the person sits at: $desktop"
    echo "- plug-in commit: $(git -C "$here" rev-parse --short HEAD) $(git -C "$here" diff --quiet || echo '(working tree has uncommitted changes)')"
    echo "- exit code of scripts/28k-e2e.sh: $status; promises passed: $passed; failed: $failed"
    [ -n "$tests" ] && echo "- the last PDE test total printed: $tests"
    echo
    echo "## What the run said"
    echo
    echo '```'
    grep -E '^  (ok|FAIL) ' "$log"
    echo '```'
    echo
    echo "## Not covered by this run"
    echo
    echo "- After a restart the last conversation reopening (the chat being where it was is P24 above; reopening needs a real agent, plan 21.15)."
    echo "- The callouts for D14's fixture and the commit draft with HEAD unchanged (waiting for the signed Amendment 1; stages 13b and 22b)."
    echo "- R1 and a chat turn that calls a jawata tool in the real chat (plan 21.15)."
    echo "- Windows and macOS: run by Harald."
} > "$out"
echo "os-check: written to $out"
[ "$status" = 0 ] && [ "$failed" = 0 ]
