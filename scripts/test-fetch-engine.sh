#!/usr/bin/env bash
# scripts/fetch-engine.sh takes the engine from the published release by default, and from a LOCAL dist when JAWATA_ENGINE_DIST names one
# (plan Stage 25a: the engine's main is ahead of its last release, so the plug-in is built and tested against the dist built from main).
#
# Runs the script in a temporary copy of the files it needs (never in this working tree), with a stubbed curl that serves a small made-up
# release archive (and logs every call) and the real jq. Nothing is downloaded and nothing of this repository's .work is touched.
# JAWATA_FETCH_SCRIPT names the script under test (default scripts/fetch-engine.sh): the planted breaks run a damaged copy of it through
# this file, and each must turn exactly the checks named in the comment above it red.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
script="${JAWATA_FETCH_SCRIPT:-$here/scripts/fetch-engine.sh}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
passed=0 failed=0
ok()   { echo "  ok    $*"; passed=$((passed + 1)); }
fail() { echo "  FAIL  $*"; failed=$((failed + 1)); }
for need in jq jar tar sha256sum; do command -v "$need" > /dev/null || { echo "$need is not installed"; exit 2; }; done

version="4.5.0"
ids="$(grep -o '<plugin id="[^"]*"' "$here/features/org.jawata.engine.feature/feature.xml" | sed -E 's/<plugin id="([^"]*)"/\1/')"

# A bundle jar is a jar whose manifest names it; its content says which dist it came from, so a copy of the wrong one is seen.
make_dist() {  # $1: folder to build, $2: label written into every file
    local d="$1" label="$2" id
    mkdir -p "$d/bundles" "$d/tools" "$d/test-bundles"
    for id in $ids; do
        printf 'Manifest-Version: 1.0\nBundle-SymbolicName: %s\nBundle-Version: 1.0.0\n' "$id" > "$tmp/mf"
        echo "$label" > "$tmp/label"
        (cd "$tmp" && jar cfm "$d/bundles/$id-1.0.0.jar" mf label)
    done
    echo "$label tool" > "$d/tools/runner.jar"
    echo "$label contracts" > "$d/test-bundles/org.jawata.mcp.tests-$version.jar"
    echo "$label bridge" > "$d/jawata.jar"
}

make_dist "$tmp/published/jawata-v$version" published
make_dist "$tmp/local" local
(cd "$tmp/published" && tar -czf "$tmp/archive.tar.gz" "jawata-v$version")
archive_sha="$(sha256sum "$tmp/archive.tar.gz" | cut -d' ' -f1)"

# The stubbed curl: serves the archive for the download, GitHub's digest for the release query, and logs the call.
mkdir -p "$tmp/bin"
cat > "$tmp/bin/curl" <<STUB
#!/usr/bin/env bash
echo "curl \$*" >> "$tmp/curl.log"
out=""
while [ \$# -gt 0 ]; do [ "\$1" = "-o" ] && out="\$2"; shift; done
if [ -n "\$out" ]; then cp "$tmp/archive.tar.gz" "\$out"; else
    printf '{"assets":[{"name":"jawata-v$version-linux-x64.tar.gz","digest":"sha256:$archive_sha"}]}'
fi
STUB
chmod +x "$tmp/bin/curl"

repo="$tmp/repo"
mkdir -p "$repo/scripts" "$repo/features/org.jawata.engine.feature" "$repo/bundles/org.jawata.eclipse" "$repo/tests/org.jawata.eclipse.tests"
cp "$script" "$repo/scripts/fetch-engine.sh"
cp "$here/scripts/lib-bundles.sh" "$repo/scripts/"
cp "$here/features/org.jawata.engine.feature/feature.xml" "$repo/features/org.jawata.engine.feature/"
echo "$version" > "$repo/engine.version"
run() { PATH="$tmp/bin:$PATH" bash "$repo/scripts/fetch-engine.sh" 2>&1; }
tree() { (cd "$repo" && find . -path ./.git -prune -o -type f -print0 | sort -z | xargs -0 sha256sum); }
calls() { [ -f "$tmp/curl.log" ] && wc -l < "$tmp/curl.log" || echo 0; }
unset JAWATA_ENGINE_DIST

echo "the published route (the variable unset)"
out="$(run)"; code=$?
want_count="$(printf '%s\n' "$ids" | grep -c .)"
if [ "$code" -eq 0 ] && [ "$out" = "engine $version fetched: $want_count bundles in $repo/.work/engine/plugins (archive sha256 $archive_sha, matches GitHub's digest)" ]; then
    ok "P1 the download route prints its line exactly as before and checks the archive against GitHub's digest"
else
    fail "P1 the download route's output changed (exit $code): $out"
fi
[ "$(cat "$repo/.work/engine/version")" = "$version" ] && ok "P2 the marker is the plain version" || fail "P2 the marker is '$(cat "$repo/.work/engine/version" 2>/dev/null)'"
if [ "$(cat "$repo/bundles/org.jawata.eclipse/agent/jawata.jar")" = "published bridge" ] && [ "$(cat "$repo/tests/org.jawata.eclipse.tests/lib/jawata-engine-contracts.jar")" = "published contracts" ]; then
    ok "P3 the bridge and the contract tests are the published release's"
else
    fail "P3 the bridge or the contract tests are not the published release's"
fi
published_tree="$(tree)"
published_calls="$(calls)"
out="$(run)"
[ "$out" = "engine $version already fetched ($want_count bundles in $repo/.work/engine/plugins)" ] && [ "$(calls)" = "$published_calls" ] \
    && ok "P4 a second run says it is already fetched and asks GitHub nothing" || fail "P4 second run: $out (calls $(calls) after $published_calls)"

echo "the local route (JAWATA_ENGINE_DIST)"
stamp="$(find "$tmp/local/bundles" "$tmp/local/tools" "$tmp/local/test-bundles" "$tmp/local/jawata.jar" -type f -printf '%T@\n' | sort -n | tail -1 | cut -d. -f1)"
calls_before="$(calls)"
out="$(JAWATA_ENGINE_DIST="$tmp/local" run)"; code=$?
[ "$code" -eq 0 ] && ok "L1 a local dist is accepted (exit 0)" || fail "L1 a local dist: exit $code: $out"
if echo "$out" | grep -qxF "engine from LOCAL dist $tmp/local; no published digest exists, none checked"; then
    ok "L2 it says in so many words that the engine is from a local dist and that no digest was checked"
else
    fail "L2 the local route does not say so: $out"
fi
if echo "$out" | grep -q "matches GitHub's digest\|archive sha256"; then fail "L3 the local route claims a digest check: $out"; else ok "L3 it never claims a digest"; fi
[ "$(calls)" = "$calls_before" ] && ok "L4 it does not ask GitHub for anything" || fail "L4 it called curl: $(tail -2 "$tmp/curl.log")"
[ "$(cat "$repo/.work/engine/version")" = "local:$tmp/local@$stamp" ] && ok "L5 the marker is local:<dist>@<mtime>" || fail "L5 the marker is '$(cat "$repo/.work/engine/version")', wanted local:$tmp/local@$stamp"
if [ "$(cat "$repo/bundles/org.jawata.eclipse/agent/jawata.jar")" = "local bridge" ] && [ "$(cat "$repo/tests/org.jawata.eclipse.tests/lib/jawata-engine-contracts.jar")" = "local contracts" ] \
    && [ "$(cat "$repo/bundles/org.jawata.eclipse/tools/runner.jar")" = "local tool" ]; then
    ok "L6 the bridge, the contract tests and the tools are the local dist's"
else
    fail "L6 the bridge, the contract tests or the tools are not the local dist's"
fi
mine="$(unzip -p "$repo/.work/engine/plugins/$(ls "$repo/.work/engine/plugins" | head -1)" label)"
[ "$(ls "$repo/.work/engine/plugins" | wc -l)" = "$want_count" ] && [ "$mine" = "local" ] && ok "L7 exactly the $want_count bundles the engine feature names, from the local dist" \
    || fail "L7 plugins: $(ls "$repo/.work/engine/plugins" | wc -l) bundles, first from '$mine'"
before="$(tree)"
out="$(JAWATA_ENGINE_DIST="$tmp/local" run)"
if echo "$out" | grep -qxF "engine from LOCAL dist $tmp/local; no published digest exists, none checked" && echo "$out" | grep -q "already fetched" && [ "$before" = "$(tree)" ]; then
    ok "L8 a second run changes nothing, says it is already fetched, and still says the engine is local"
else
    fail "L8 second local run: $out"
fi
touch -d '+2 minutes' "$tmp/local/jawata.jar"
out="$(JAWATA_ENGINE_DIST="$tmp/local" run)"
if echo "$out" | grep -q "already fetched"; then fail "L9 a rebuilt dist (newer file) was taken for the one already fetched: $out"; else ok "L9 a dist rebuilt since the last fetch is fetched again"; fi
marker="$(cat "$repo/.work/engine/version")"
case "$marker" in
    "local:$tmp/local@$stamp") fail "L10 the marker did not move" ;;
    "local:$tmp/local@"*) ok "L10 and the marker moved with it" ;;
    *) fail "L10 the marker is '$marker', not a local one" ;;
esac
before="$(tree)"
out="$(JAWATA_ENGINE_DIST="$tmp/nowhere" run)"; code=$?
if [ "$code" -ne 0 ] && echo "$out" | grep -q "$tmp/nowhere" && [ "$before" = "$(tree)" ]; then
    ok "L11 a dist folder that is not there stops the run, names it, and changes nothing"
else
    fail "L11 a missing dist folder: exit $code: $out"
fi
mkdir -p "$tmp/empty/bundles"
out="$(JAWATA_ENGINE_DIST="$tmp/empty" run)"; code=$?
if [ "$code" -ne 0 ] && [ "$before" = "$(tree)" ]; then ok "L12 a dist without the engine's bundles stops the run and changes nothing"; else fail "L12 an empty dist: exit $code: $out"; fi

echo "back to the published route"
out="$(run)"; code=$?
if [ "$code" -eq 0 ] && [ "$(cat "$repo/.work/engine/version")" = "$version" ] && echo "$out" | grep -q "matches GitHub's digest" \
    && [ "$(cat "$repo/bundles/org.jawata.eclipse/agent/jawata.jar")" = "published bridge" ]; then
    ok "B1 with the variable unset again, the published release replaces the local one, digest checked"
else
    fail "B1 back to the published route: exit $code: $out"
fi
[ "$(tree)" = "$published_tree" ] && ok "B2 and the tree is byte for byte what the first published run left" || fail "B2 the tree differs from the first published run's"

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
