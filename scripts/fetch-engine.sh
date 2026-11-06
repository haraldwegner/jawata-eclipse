#!/usr/bin/env bash
# Fetch the released jawata engine into .work/engine/plugins for the plug-in's build (plan Stage 15, contract R9).
#
# The engine is never built here: the plug-in consumes the bundles of ONE released jawata-mcp dist, named by the
# file engine.version at the repository root. This script downloads that release's Linux archive from GitHub, checks it
# against the digest GitHub publishes for the asset, and copies exactly the bundles the engine feature names
# (features/org.jawata.engine.feature/feature.xml) into .work/engine/plugins, where the target platform reads them.
# The bundles are the same in every OS archive; only SWT's platform fragments differ and are not copied.
#
# Needs: curl, jq, tar, sha256sum, unzip. No GitHub login (public release). Run before `mvn verify`. Idempotent.
#
# JAWATA_ENGINE_DIST=<absolute folder> takes the engine from a LOCAL dist (the folder the engine's own build leaves, with bundles/, tools/,
# test-bundles/ and jawata.jar) in place of the download, for the time between an engine release and the commits its main has gained
# since (plan Stage 25a). Nothing is downloaded and NO digest is checked, because none exists for a local build: the script says so, and
# .work/engine/version then reads local:<dist>@<mtime of the newest file taken from it>, so a rebuilt dist is fetched again and a run
# without the variable goes back to the release. With the variable unset nothing below differs from before.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
. "$here/scripts/lib-bundles.sh"
version="$(tr -d '[:space:]' < "$here/engine.version")"
repo="haraldwegner/jawata-mcp"
asset="jawata-v${version}-linux-x64.tar.gz"
dir="$here/.work/engine"
download="$here/.work/engine-download"

tools="$here/bundles/org.jawata.eclipse/tools"   # the release's tools/ folder (the test runner, JUnit's console launcher, JaCoCo's agent, PIT), shipped inside the plug-in
# The release's test-support jar, which holds the engine's Stage 8 contract tests and the note fixtures (plan 25a.1): the plug-in's test bundle
# carries it on its own class path, so the contracts the engine's own hosts pass are run against EclipseHost here, from the same release tag.
contracts="$here/tests/org.jawata.eclipse.tests/lib/jawata-engine-contracts.jar"
# The release's bridge program (jawata.jar, 22 KB: it holds no agent logic and starts no framework). The chat in Eclipse starts it as
# `java -jar jawata.jar --acp --url <the engine's listener>`, which carries the chat's lines to the engine's agent face (plan 25a.4).
agent="$here/bundles/org.jawata.eclipse/agent/jawata.jar"
local_dist="${JAWATA_ENGINE_DIST:-}"
marker="$version"
if [ -n "$local_dist" ]; then
    local_dist="$(cd "$local_dist" 2>/dev/null && pwd)" || { echo "JAWATA_ENGINE_DIST names ${JAWATA_ENGINE_DIST}, which is not a folder" >&2; exit 1; }
    for need in "$local_dist/bundles" "$local_dist/tools" "$local_dist/test-bundles" "$local_dist/jawata.jar"; do
        [ -e "$need" ] || { echo "the local dist $local_dist has no $(basename "$need") (set JAWATA_ENGINE_DIST to the folder the engine's build leaves)" >&2; exit 1; }
    done
    stamp="$(find "$local_dist/bundles" "$local_dist/tools" "$local_dist/test-bundles" "$local_dist/jawata.jar" -type f -printf '%T@\n' | sort -n | tail -1 | cut -d. -f1)"
    marker="local:$local_dist@$stamp"
    echo "engine from LOCAL dist $local_dist; no published digest exists, none checked"
fi
if [ -f "$dir/version" ] && [ "$(cat "$dir/version")" = "$marker" ] && [ "$(ls "$dir/plugins" 2>/dev/null | wc -l)" -gt 0 ] \
    && [ "$(ls "$tools" 2>/dev/null | wc -l)" -gt 0 ] && [ -f "$contracts" ] && [ -f "$agent" ]; then
    if [ -n "$local_dist" ]; then echo "engine already fetched from the LOCAL dist ($(ls "$dir/plugins" | wc -l) bundles in $dir/plugins)"
    else echo "engine $version already fetched ($(ls "$dir/plugins" | wc -l) bundles in $dir/plugins)"; fi
    exit 0
fi

if [ -z "$local_dist" ]; then
    mkdir -p "$download"
    if [ ! -f "$download/$asset" ]; then
        curl -sSfL -o "$download/$asset.part" "https://github.com/$repo/releases/download/v${version}/$asset"
        mv "$download/$asset.part" "$download/$asset"
    fi
    want="$(curl -sSf "https://api.github.com/repos/$repo/releases/tags/v${version}" \
        | jq -r --arg a "$asset" '.assets[] | select(.name == $a) | .digest | sub("^sha256:"; "")')"
    have="$(sha256sum "$download/$asset" | cut -d' ' -f1)"
    if [ -z "$want" ] || [ "$want" != "$have" ]; then
        echo "the archive's sha256 ($have) does not match GitHub's digest (${want:-none found}) — not using it" >&2
        rm -f "$download/$asset"
        exit 1
    fi

    rm -rf "$dir" "$download/unpacked"
    mkdir -p "$dir/plugins" "$download/unpacked"
    tar -xzf "$download/$asset" -C "$download/unpacked"
    bundles="$(find "$download/unpacked" -type d -name bundles | head -1)"
    [ -n "$bundles" ] || { echo "no bundles folder in $asset" >&2; exit 1; }
else
    # The local dist is read where it stands. Nothing is checked about a build of main: the engine's own gates are the check on it.
    rm -rf "$dir"
    mkdir -p "$dir/plugins"
    bundles="$local_dist/bundles"
fi

# The bundles the engine feature names, by symbolic name, found by reading each jar's manifest.
ids="$(grep -o '<plugin id="[^"]*"' "$here/features/org.jawata.engine.feature/feature.xml" | sed -E 's/<plugin id="([^"]*)"/\1/')"
for id in $ids; do
    found=""
    for jar in "$bundles"/*.jar; do
        bsn="$(bundle_symbolic_name "$jar")"
        if [ "$bsn" = "$id" ]; then cp "$jar" "$dir/plugins/"; found=1; break; fi
    done
    [ -n "$found" ] || { echo "the release $version has no bundle $id" >&2; exit 1; }
done
# The engine's run_tests, coverage and mutation tools start their runner from the dist's tools/ folder (setting jawata.tools.dir). The headless
# engine finds it beside its own bundles; inside Eclipse nothing does, so the plug-in ships the same folder and points the setting at it
# (found 2026-10-30 by the Stage 26 dogfood: run_tests was refused in Eclipse for want of it).
if [ -n "$local_dist" ]; then dist_tools="$local_dist/tools"; else dist_tools="$(find "$download/unpacked" -type d -name tools | head -1)"; fi
[ -n "$dist_tools" ] && ls "$dist_tools"/*.jar > /dev/null 2>&1 || { echo "no tools folder with jars in ${local_dist:-$asset}" >&2; exit 1; }
rm -rf "$tools" && mkdir -p "$tools" && cp "$dist_tools"/*.jar "$tools/"
if [ -n "$local_dist" ]; then support="$(ls "$local_dist"/test-bundles/org.jawata.mcp.tests-*.jar 2>/dev/null | head -1)"
else support="$(find "$download/unpacked" -type f -name "org.jawata.mcp.tests-${version}.jar" | head -1)"; fi
[ -n "$support" ] || { echo "no org.jawata.mcp.tests-${version}.jar (the engine's contract tests) in ${local_dist:-$asset}" >&2; exit 1; }
mkdir -p "$(dirname "$contracts")" && cp "$support" "$contracts"
if [ -n "$local_dist" ]; then bridge="$local_dist/jawata.jar"; else bridge="$(find "$download/unpacked" -maxdepth 2 -type f -name jawata.jar | head -1)"; fi
[ -n "$bridge" ] || { echo "no jawata.jar (the --acp bridge) in ${local_dist:-$asset}" >&2; exit 1; }
mkdir -p "$(dirname "$agent")" && cp "$bridge" "$agent"
echo "$marker" > "$dir/version"
if [ -n "$local_dist" ]; then
    echo "engine fetched from the LOCAL dist $local_dist: $(ls "$dir/plugins" | wc -l) bundles in $dir/plugins (no digest exists for a local build, none checked)"
else
    echo "engine $version fetched: $(ls "$dir/plugins" | wc -l) bundles in $dir/plugins (archive sha256 $have, matches GitHub's digest)"
fi
