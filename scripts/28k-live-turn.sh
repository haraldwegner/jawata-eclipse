#!/usr/bin/env bash
# Plan Stage 21, step 21.15 (spec D3, decision C): ONE real agent turn through the chat in Eclipse. Opt-in; never part of
# scripts/28k-e2e.sh or of `mvn verify`.
#
# It costs tokens and needs the network and the person's `claude` login (the agent adapter reads the login that `claude` keeps in the home
# folder). The adapter is @agentclientprotocol/claude-agent-acp@0.93.0, started by `npx -y`; the model is JAWATA_LIVE_MODEL (default
# claude-haiku-5-5, the cheapest). The test asks three things that need jawata's tools (health_check, a search, a rename on a fixture
# project) and passes when the page's stream holds a completed tool call with a jawata title (tests/.../LiveTurnTest.java).
#
# Usage: scripts/28k-live-turn.sh
# Needs: the built update site (xvfb-run -a mvn -B verify), the chat page built (scripts/build-chat-page.sh), the Java download that
# scripts/28k-e2e.sh keeps in .work/dl-java, xvfb-run, node and npx on the path. Installs the update site into a fresh copy of the
# download (.work/live-turn/) and runs the one test in it. Exit 0 when the test passed, 1 when it failed, 2 when something it needs is missing
# (nothing was spent in that case).
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
work="$here/.work"
site="${JAWATA_SITE:-$here/repository/target/repository}"
release="https://download.eclipse.org/releases/2026-09"
feature="org.jawata.eclipse.feature.feature.group"
archive="$(ls "$work"/dl-java/eclipse-java-2026-09-R-linux-gtk-x86_64.tar.gz 2>/dev/null | head -1)"

[ -f "$site/content.jar" ] || { echo "28k-live-turn: no update site at $site: run xvfb-run -a mvn -B verify"; exit 2; }
[ -f "$work/chat-conformance/conformance.html" ] || { echo "28k-live-turn: no chat test page: run scripts/build-chat-page.sh"; exit 2; }
[ -n "$archive" ] || { echo "28k-live-turn: no Java download in $work/dl-java: run scripts/28k-e2e.sh once"; exit 2; }
command -v xvfb-run >/dev/null || { echo "28k-live-turn: xvfb-run is not installed"; exit 2; }
# JAWATA_LIVE_NPX replaces npx with another program that speaks the agent protocol; the plumbing is then checked without tokens (the
# test fails its assertions, because a recorded session makes no jawata call, but it prints what the page saw)
npx="${JAWATA_LIVE_NPX:-$(command -v npx)}" || { echo "28k-live-turn: npx is not on the path"; exit 2; }
[ -f "$HOME/.claude/.credentials.json" ] || { echo "28k-live-turn: no claude login found in $HOME/.claude"; exit 2; }
# the conformance page is the one the test loads: it must have the live driver
grep -q 'playLive' "$work/chat-conformance/conformance.js" || { echo "28k-live-turn: the built test page predates the live driver: run scripts/build-chat-page.sh"; exit 2; }

run="$work/live-turn"
rm -rf "$run" && mkdir -p "$run"
tar -xzf "$archive" -C "$run" || { echo "28k-live-turn: could not unpack $archive"; exit 2; }
eclipse="$run/eclipse"
"$eclipse/eclipse" -nosplash -application org.eclipse.equinox.p2.director \
    -repository "file:$site,$release" -installIU "$feature" > "$run/director.out" 2>&1 \
    || { echo "28k-live-turn: the update site did not install; see $run/director.out"; exit 2; }

# the agent gets a Claude configuration folder that holds only the login, so it cannot reach the person's own MCP servers (the first live run
# passed on them: mcp__jawata-javata-dev__* and others, not the engine inside Eclipse); the test passes only on mcp__jawata__* tools
config="$run/claude-config"
mkdir -p "$config" && cp "$HOME/.claude/.credentials.json" "$config/.credentials.json" && chmod 600 "$config/.credentials.json"

out="$run/live-turn.out"
echo "28k-live-turn: one real agent turn, three prompts; output in $out"
(cd "$here" && JAWATA_LIVE_CONFIG_DIR="$config" JAWATA_LIVE_NPX="$npx" JAWATA_LIVE_MODEL="${JAWATA_LIVE_MODEL:-claude-haiku-5-5}" \
    xvfb-run -a mvn -B verify -pl releng/org.jawata.eclipse.target,bundles/org.jawata.eclipse,bundles/org.jawata.eclipse.chat,bundles/org.jawata.eclipse.terminal,tests/org.jawata.eclipse.tests -am \
    -Djawata.live=true -Dtest="${JAWATA_LIVE_TEST:-LiveTurnTest}" -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Djawata.download="$eclipse" -Djawata.profile="epp.package.java" -Djawata.product="org.eclipse.epp.package.java.product") > "$out" 2>&1
rc=$?
grep -h 'jawata-live-' "$out" | head -8
summary="$(grep -E '^Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$out" | tail -1)"
if [ "$rc" = 0 ] && echo "$summary" | grep -q '^Tests run: 1, Failures: 0, Errors: 0, Skipped: 0$'; then
    echo "28k-live-turn: PASSED ($summary)"
    exit 0
fi
echo "28k-live-turn: FAILED (exit $rc, ${summary:-no test summary}); output: $out"
exit 1
