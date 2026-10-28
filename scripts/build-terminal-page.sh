#!/usr/bin/env bash
# Plan Stage 13c, step 2: vendors xterm.js and its fit addon (both MIT) into the jawata terminal bundle's page, each pinned by version and by
# the package's own sha512, so the page never loads anything from the network and a floating version cannot change what the person sees.
#
# Usage: scripts/build-terminal-page.sh            (needs node's npm and network once; the vendored files are committed)
# It writes bundles/org.jawata.eclipse.terminal/page/vendor/{xterm.js,xterm.css,addon-fit.js,LICENSE,VERSION}. Changing a pin below and
# running this again is how a package is updated.
set -euo pipefail

xterm_version="5.5.0"
xterm_integrity="sha512-hqJHYaQb5OptNunnyAnkHyM8aCjZ1MEIDTQu1iIbbTD/xops91NB5yq1ZK/dC2JDbVWtF23zUtl9JE2NqwT87A=="
fit_version="0.10.0"
fit_integrity="sha512-UFYkDm4HUahf2lnEyHvio51TNGiLK66mqP2JoATy7hRZeXaGMRDr00JiSF7m63vR5WKATF605yEggJKsw0JpMQ=="

here="$(cd "$(dirname "$0")/.." && pwd)"
out="$here/bundles/org.jawata.eclipse.terminal/page/vendor"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

command -v npm >/dev/null || { echo "npm is not installed"; exit 2; }

fetch() {  # $1 package, $2 version, $3 pinned integrity, $4 tarball name → the package unpacked under $work/$4
    (cd "$work" && npm pack "$1@$2" --silent >/dev/null)
    local tgz="$work/$4-$2.tgz" actual
    actual="sha512-$(openssl dgst -sha512 -binary "$tgz" | base64 -w0)"
    [ "$actual" = "$3" ] || { echo "$1 $2: the package's sha512 is $actual, not the pinned $3"; exit 1; }
    mkdir -p "$work/$4"
    tar -xzf "$tgz" -C "$work/$4"
}

fetch "@xterm/xterm" "$xterm_version" "$xterm_integrity" "xterm-xterm"
fetch "@xterm/addon-fit" "$fit_version" "$fit_integrity" "xterm-addon-fit"

mkdir -p "$out"
cp "$work/xterm-xterm/package/lib/xterm.js" "$out/xterm.js"
cp "$work/xterm-xterm/package/css/xterm.css" "$out/xterm.css"
cp "$work/xterm-addon-fit/package/lib/addon-fit.js" "$out/addon-fit.js"
cp "$work/xterm-xterm/package/LICENSE" "$out/LICENSE"
printf '@xterm/xterm %s\n%s\n@xterm/addon-fit %s\n%s\n' "$xterm_version" "$xterm_integrity" "$fit_version" "$fit_integrity" > "$out/VERSION"
echo "xterm.js $xterm_version and addon-fit $fit_version vendored into $out"
