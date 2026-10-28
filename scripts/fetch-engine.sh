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
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
. "$here/scripts/lib-bundles.sh"
version="$(tr -d '[:space:]' < "$here/engine.version")"
repo="haraldwegner/jawata-mcp"
asset="jawata-v${version}-linux-x64.tar.gz"
dir="$here/.work/engine"
download="$here/.work/engine-download"

if [ -f "$dir/version" ] && [ "$(cat "$dir/version")" = "$version" ] && [ "$(ls "$dir/plugins" 2>/dev/null | wc -l)" -gt 0 ]; then
    echo "engine $version already fetched ($(ls "$dir/plugins" | wc -l) bundles in $dir/plugins)"
    exit 0
fi

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
echo "$version" > "$dir/version"
echo "engine $version fetched: $(ls "$dir/plugins" | wc -l) bundles in $dir/plugins (archive sha256 $have, matches GitHub's digest)"
