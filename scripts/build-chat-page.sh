#!/usr/bin/env bash
# Build the chat's page into bundles/org.jawata.eclipse.chat/page/ (plan Stage 21, step 21.1; gitignored, part of the bundle by
# build.properties' bin.includes).
#
# The chat is never built from source here: the plug-in consumes the library packages of ONE jawata-chat commit, named by the file
# chat.ref at the repository root (as engine.version names the engine). This script
#   1. finds the chat checkout (JAWATA_CHAT, default ../jawata-chat) and refuses it when its HEAD is not the commit in chat.ref
#      (JAWATA_CHAT_ALLOW_DRIFT=1 builds anyway and says so);
#   2. runs `npm ci` and `npm run pack` there ONLY when packed/ lacks the three tarballs or a source is newer than one of them;
#   3. installs the three tarballs, all together in one command, plus pinned webpack into .work/chat-page, and bundles scripts/chat-page/entry.js;
#   4. writes index.html, chat.js, the licence texts (LICENSE.txt: jawata's AGPL-3.0-only; THIRD-PARTY-LICENSES.txt and
#      chat.js.LICENSE.txt: the bundled libraries) and view-contract.json (the contract number the page speaks, read from chat-core's
#      compiled view contract) into the page folder;
#   5. prints one summary line.
# The page is the chat core and view on the Eclipse host adapter (scripts/chat-page/host-adapter.js, step 21.4); the VS Code webview entry
# cannot be used, it calls acquireVsCodeApi. A second bundle, scripts/chat-page/conformance.js, holds chat-core's host-port checks run
# against the adapter; it is written to .work/chat-conformance/ and is not part of the plug-in.
#
# Run it BEFORE `mvn verify` (like scripts/fetch-engine.sh); it is not part of the Tycho build. Needs: node 20+, npm, git, and the network
# when the chat checkout's packages or webpack are not installed yet. Idempotent. Tested by scripts/test-build-chat-page.sh.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
chat="${JAWATA_CHAT:-$here/../jawata-chat}"
webpack_version="5.112.0"
webpack_cli_version="5.1.4"
page="$here/bundles/org.jawata.eclipse.chat/page"
scratch="$here/.work/chat-page"

die() { echo "build-chat-page: $*" >&2; exit 1; }

command -v node >/dev/null && command -v npm >/dev/null || die "node and npm are needed on PATH"
[ -f "$here/chat.ref" ] || die "chat.ref is missing: it names the jawata-chat commit this plug-in is built against"
ref="$(tr -d '[:space:]' < "$here/chat.ref")"
[ -d "$chat/packages/chat-core" ] || die "no jawata-chat checkout at $chat (set JAWATA_CHAT)"
chat="$(cd "$chat" && pwd)"
head="$(git -C "$chat" rev-parse HEAD)" || die "$chat is not a git checkout"
if [ "$head" != "$ref" ]; then
    if [ "${JAWATA_CHAT_ALLOW_DRIFT:-}" = 1 ]; then
        echo "build-chat-page: WARNING: $chat is at $head, chat.ref names $ref (JAWATA_CHAT_ALLOW_DRIFT=1)" >&2
    else
        die "$chat is at commit $head but chat.ref names $ref. Check out $ref there, or change chat.ref to the commit you mean, or set JAWATA_CHAT_ALLOW_DRIFT=1 to build anyway."
    fi
fi
if [ -n "$(git -C "$chat" status --porcelain --untracked-files=no)" ]; then
    echo "build-chat-page: WARNING: $chat has uncommitted changes; the page is built from them, not from $ref alone" >&2
fi

# 2. the tarballs: rebuilt only when missing or older than the sources they are packed from
tarballs() { ls "$chat"/packed/jawata-acp-contract-*.tgz "$chat"/packed/jawata-chat-core-*.tgz "$chat"/packed/jawata-chat-view-*.tgz 2>/dev/null || true; }
needs_pack() {
    [ "$(tarballs | wc -l)" -eq 3 ] || return 0
    local oldest
    oldest="$(ls -tr $(tarballs) | head -1)"
    [ -n "$(cd "$chat" && find packages/acp-contract packages/chat-core packages/chat-view \
        \( -name node_modules -o -name dist -o -name test \) -prune -o -type f -newer "$oldest" -print -quit; \
        find package.json package-lock.json scripts/build.mjs -newer "$oldest" -print -quit)" ]
}
if needs_pack; then
    echo "build-chat-page: packing the chat packages in $chat"
    (cd "$chat" && npm ci --no-audit --no-fund && npm run pack)
else
    echo "build-chat-page: the tarballs in $chat/packed are up to date; not repacking"
fi
[ "$(tarballs | wc -l)" -eq 3 ] || die "$chat/packed does not hold exactly the three tarballs (acp-contract, chat-core, chat-view) after packing"
tars=($(tarballs))

# 3. the scratch folder: the tarballs, in ONE install, and the bundler; reinstalled only when either changed
mkdir -p "$scratch"
stamp="$(sha256sum "${tars[@]}" | cut -d' ' -f1 | tr '\n' ' ')webpack@$webpack_version webpack-cli@$webpack_cli_version"
if [ ! -d "$scratch/node_modules/webpack" ] || [ "$(cat "$scratch/install.stamp" 2>/dev/null)" != "$stamp" ]; then
    rm -rf "$scratch/node_modules" "$scratch/package-lock.json" "$scratch/install.stamp"
    printf '{ "name": "jawata-chat-page-scratch", "private": true, "version": "0.0.0" }\n' > "$scratch/package.json"
    (cd "$scratch" && npm install --no-audit --no-fund --save-exact "${tars[@]}" "webpack@$webpack_version" "webpack-cli@$webpack_cli_version" > install.log 2>&1) \
        || { tail -20 "$scratch/install.log" >&2; die "npm install in $scratch failed (full log: $scratch/install.log)"; }
    echo "$stamp" > "$scratch/install.stamp"
fi
cp "$here/scripts/chat-page/"*.js "$here/scripts/chat-page/webpack.config.cjs" "$scratch/"
rm -rf "$scratch/out"
(cd "$scratch" && node node_modules/webpack/bin/webpack.js --config webpack.config.cjs --json stats.json > webpack.log 2>&1) \
    || { tail -30 "$scratch/webpack.log" >&2; die "webpack failed (full log: $scratch/webpack.log)"; }
node -e 'const s=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); if(s.errors&&s.errors.length){console.error(s.errors.map(e=>e.message).join("\n"));process.exit(1)}' "$scratch/stats.json" \
    || die "webpack reported errors"

# 4. the page folder
contract="$(cd "$scratch" && node --input-type=module -e "import('@jawata/chat-core/view').then(m => { if (!Number.isInteger(m.VIEW_CONTRACT_VERSION)) { process.exit(1); } console.log(m.VIEW_CONTRACT_VERSION); })")" \
    || die "could not read VIEW_CONTRACT_VERSION from the installed @jawata/chat-core"
rm -rf "$page"
mkdir -p "$page"
cp "$here/scripts/chat-page/index.html" "$page/index.html"
cp "$scratch/out/chat.js" "$page/chat.js"
# the host-port conformance bundle stays OUT of the plug-in: a test loads it from here
conformance="$here/.work/chat-conformance"
rm -rf "$conformance"; mkdir -p "$conformance"
cp "$here/scripts/chat-page/conformance.html" "$scratch/out/conformance.js" "$conformance/"
[ -f "$scratch/out/chat.js.LICENSE.txt" ] && cp "$scratch/out/chat.js.LICENSE.txt" "$page/chat.js.LICENSE.txt"
{
    echo "The jawata chat page: jawata's own code (@jawata/chat-core, @jawata/chat-view, @jawata/acp-contract) is licensed AGPL-3.0-only."
    echo "Source: the jawata-chat repository, commit $head. The licences of the third-party libraries the page bundles are in THIRD-PARTY-LICENSES.txt."
    echo
    cat "$scratch/node_modules/@jawata/chat-core/LICENSE"
} > "$page/LICENSE.txt"
node "$here/scripts/chat-page/collect-licences.mjs" "$scratch/stats.json" "$scratch/node_modules" "$page/THIRD-PARTY-LICENSES.txt" > /dev/null
printf '{ "contract": %s, "jawataChat": "%s" }\n' "$contract" "$head" > "$page/view-contract.json"

# 5. one line
size() { wc -c < "$1" | tr -d ' '; }
echo "chat page: index.html $(size "$page/index.html") B, chat.js $(size "$page/chat.js") B ($(gzip -c "$page/chat.js" | wc -c | tr -d ' ') B gzipped), licences $(( $(size "$page/LICENSE.txt") + $(size "$page/THIRD-PARTY-LICENSES.txt") + $( [ -f "$page/chat.js.LICENSE.txt" ] && size "$page/chat.js.LICENSE.txt" || echo 0) )) B, view contract $contract, jawata-chat ${head:0:10}"
