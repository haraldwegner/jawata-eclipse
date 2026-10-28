#!/usr/bin/env bash
# scripts/build-chat-page.sh builds the chat's page, pins the chat checkout, and does not repack when nothing changed.
#
# Runs the build in a temporary copy of the files it needs (never in this working tree), against the chat checkout JAWATA_CHAT
# (default ../jawata-chat). The first real build needs node, npm and the network (webpack is installed into the copy's scratch
# folder); the chat checkout's packed/ folder is the cache the second run is checked against. Red controls: a chat.ref that
# names another commit must fail with a message; a page without its parts must fail the checks below.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
chat="${JAWATA_CHAT:-$here/../jawata-chat}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
passed=0 failed=0
ok()   { echo "  ok    $*"; passed=$((passed + 1)); }
fail() { echo "  FAIL  $*"; failed=$((failed + 1)); }

[ -d "$chat/packages/chat-core" ] || { echo "no chat checkout at $chat (set JAWATA_CHAT)"; exit 2; }

# The copy: the script, its helper files, the pin and the bundle's build.properties; nothing else of this repository.
copy="$tmp/repo"
mkdir -p "$copy/scripts" "$copy/bundles/org.jawata.eclipse.chat"
cp "$here/scripts/build-chat-page.sh" "$copy/scripts/" 2>/dev/null
cp -r "$here/scripts/chat-page" "$copy/scripts/" 2>/dev/null
cp "$here/chat.ref" "$copy/" 2>/dev/null
cp "$here/bundles/org.jawata.eclipse.chat/build.properties" "$copy/bundles/org.jawata.eclipse.chat/"
page="$copy/bundles/org.jawata.eclipse.chat/page"
run() { JAWATA_CHAT="$chat" bash "$copy/scripts/build-chat-page.sh" "$@"; }

echo "the pin"
cp "$copy/chat.ref" "$tmp/chat.ref.good" 2>/dev/null
echo "0000000000000000000000000000000000000000" > "$copy/chat.ref"
out="$(run 2>&1)"; code=$?
if [ "$code" -ne 0 ] && echo "$out" | grep -q 'chat.ref' && echo "$out" | grep -q '0000000000000000000000000000000000000000' && [ ! -e "$page/index.html" ]; then
    ok "a chat.ref naming another commit fails with a message naming it, and builds nothing"
else
    fail "a chat.ref naming another commit: exit $code, output: $out"
fi
cp "$tmp/chat.ref.good" "$copy/chat.ref"

echo "the build"
out="$(run 2>&1)"; code=$?
if [ "$code" -eq 0 ]; then ok "the build exits 0 (last line: $(echo "$out" | tail -1))"; else fail "the build exits $code: $(echo "$out" | tail -5)"; fi
html="$(ls "$page"/*.html 2>/dev/null | head -1)"; js="$(ls "$page"/*.js 2>/dev/null | head -1)"
[ -s "${html:-/nonexistent}" ] && ok "page/ holds a non-empty html ($(basename "$html"), $(wc -c < "$html") bytes)" || fail "no non-empty .html in page/"
[ -s "${js:-/nonexistent}" ] && ok "page/ holds a non-empty js ($(basename "$js"), $(wc -c < "$js") bytes)" || fail "no non-empty .js in page/"
if [ -n "$html" ] && grep -q "<script[^>]*src=\"$(basename "$js")\"" "$html" && grep -q 'id="root"' "$html"; then
    ok "the html loads the js and has the root element"
else
    fail "the html does not load $(basename "${js:-none}") or has no root element"
fi
[ -n "$js" ] && grep -q 'session-banner' "$js" && ok "the js holds the chat view" || fail "the js does not hold the chat view"
if [ -n "$js" ] && grep -q 'jawataHostCall' "$js" && grep -q 'jawataHostDeliver' "$js" && ! grep -q 'acquireVsCodeApi()' "$js"; then
    ok "the js is the chat on the Eclipse host adapter: it speaks jawataHostCall and jawataHostDeliver and does not call acquireVsCodeApi"
else
    fail "the js is not the chat on the Eclipse host adapter"
fi
conformance="$copy/.work/chat-conformance"
if [ -s "$conformance/conformance.js" ] && [ -s "$conformance/conformance.html" ] && grep -q 'runConformance' "$conformance/conformance.js"; then
    ok "the host-port conformance bundle is written to .work/chat-conformance/"
else
    fail "no conformance bundle in .work/chat-conformance/"
fi
if ! ls "$page" | grep -qi conformance && ! grep -q 'runConformance' "$js"; then
    ok "and none of it is in the page folder that ships"
else
    fail "conformance code reached the page folder that ships"
fi
lic="$(ls "$page" 2>/dev/null | grep -i 'licen[sc]e' | head -20)"
if [ -s "$page/LICENSE.txt" ] && grep -q 'AFFERO' "$page/LICENSE.txt" && [ -s "$page/THIRD-PARTY-LICENSES.txt" ] && grep -qi 'react' "$page/THIRD-PARTY-LICENSES.txt"; then
    ok "page/ holds jawata's AGPL text and the third-party licences (react among them): $(echo $lic)"
else
    fail "licence files missing or empty in page/: $(echo $lic)"
fi
want="$(sed -nE 's/^export const VIEW_CONTRACT_VERSION = ([0-9]+);.*/\1/p' "$chat/packages/chat-core/src/view.ts")"
got="$(node -e 'const j=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); if(!Number.isInteger(j.contract)) process.exit(1); console.log(j.contract)' "$page/view-contract.json" 2>/dev/null)"
if [ -n "$want" ] && [ "$got" = "$want" ]; then ok "view-contract.json holds the integer $got, the one in chat-core's source"; else fail "view-contract.json says '${got:-nothing}', chat-core's source says '$want'"; fi

echo "the second run"
before="$(stat -c '%n %Y' "$chat"/packed/*.tgz 2>/dev/null)"
out2="$(run 2>&1)"; code2=$?
after="$(stat -c '%n %Y' "$chat"/packed/*.tgz 2>/dev/null)"
if [ "$code2" -eq 0 ] && [ -n "$before" ] && [ "$before" = "$after" ] && ! echo "$out2" | grep -q 'npm run pack'; then
    ok "a second run exits 0 and leaves the three tarballs untouched ($(echo "$before" | wc -l) files, same timestamps)"
else
    fail "a second run: exit $code2, tarballs before/after differ or repacked: $out2"
fi

echo "the repository"
if git -C "$here" check-ignore -q bundles/org.jawata.eclipse.chat/page/index.html; then ok "the page folder is git-ignored"; else fail "bundles/org.jawata.eclipse.chat/page/ is not git-ignored"; fi
if grep -Eq '^bin\.includes *=.*page/' "$here/bundles/org.jawata.eclipse.chat/build.properties" || tr -d '\\\n' < "$here/bundles/org.jawata.eclipse.chat/build.properties" | grep -Eq 'bin\.includes *=.*page/'; then
    ok "build.properties puts page/ into the bundle"
else
    fail "build.properties' bin.includes does not name page/"
fi

echo "$passed ok, $failed failed"
[ "$failed" -eq 0 ]
