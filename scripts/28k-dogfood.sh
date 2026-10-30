#!/usr/bin/env bash
# Plan Stage 26 (contract §18 ring 3): jawata-mcp developed inside Eclipse with the plug-in. Opt-in; never part of scripts/28k-e2e.sh or of
# `mvn verify`.
#
# It clones jawata-mcp at the tag engine.version names into .work/dogfood/ (a scratch clone, so a refactoring writes there and never into the
# person's checkout), builds the engine dist from it for the class path, installs the built update site into a fresh copy of the Java
# download, and runs the one test (tests/.../DogfoodTest.java) inside it. The test imports the clone's bundles as Java projects of the
# Eclipse workspace and works the engine inside Eclipse over MCP, the 44 tools included; its report is written to .work/dogfood/dogfood-report.tsv.
#
# Usage: JAWATA_MCP=<a jawata-mcp checkout that has the tag engine.version names> scripts/28k-dogfood.sh
# Needs: the built update site (xvfb-run -a mvn -B verify), the Java download that scripts/28k-e2e.sh keeps in .work/dl-java, xvfb-run, git, mvn.
# Exit 0 when the test passed, 1 when it failed, 2 when something it needs is missing.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
work="$here/.work"
site="${JAWATA_SITE:-$here/repository/target/repository}"
feature="org.jawata.eclipse.feature.feature.group"
release="https://download.eclipse.org/releases/2026-09"
version="$(tr -d '[:space:]' < "$here/engine.version")"
archive="$(ls "$work"/dl-java/eclipse-java-2026-09-R-linux-gtk-x86_64.tar.gz 2>/dev/null | head -1)"
source_repo="${JAWATA_MCP:-}"

[ -f "$site/content.jar" ] || { echo "28k-dogfood: no update site at $site: run xvfb-run -a mvn -B verify"; exit 2; }
[ -n "$archive" ] || { echo "28k-dogfood: no Java download in $work/dl-java: run scripts/28k-e2e.sh once"; exit 2; }
command -v xvfb-run >/dev/null || { echo "28k-dogfood: xvfb-run is not installed"; exit 2; }
[ -n "$source_repo" ] && [ -d "$source_repo/.git" ] || { echo "28k-dogfood: JAWATA_MCP must name a jawata-mcp checkout"; exit 2; }

run="$work/dogfood"
rm -rf "$run" && mkdir -p "$run"
git clone -q "$source_repo" "$run/jawata-mcp" && git -C "$run/jawata-mcp" checkout -q "v$version" || { echo "28k-dogfood: could not clone jawata-mcp at v$version"; exit 2; }
dist="${JAWATA_DOGFOOD_DIST:-$run/jawata-mcp/build/dist/target/dist}"
if [ ! -d "$dist/bundles" ]; then
    echo "28k-dogfood: building the engine dist from the clone"
    (cd "$run/jawata-mcp" && mvn -o -B -q -f build/pom.xml clean package -DskipTests) > "$run/engine-build.out" 2>&1 || { echo "28k-dogfood: the engine dist did not build; see $run/engine-build.out"; exit 2; }
fi

tar -xzf "$archive" -C "$run" || { echo "28k-dogfood: could not unpack $archive"; exit 2; }
eclipse="$run/eclipse"
"$eclipse/eclipse" -nosplash -application org.eclipse.equinox.p2.director \
    -repository "file:$site,$release" -installIU "$feature" > "$run/director.out" 2>&1 \
    || { echo "28k-dogfood: the update site did not install; see $run/director.out"; exit 2; }

out="$run/dogfood.out"
echo "28k-dogfood: the engine inside Eclipse on the jawata-mcp clone; output in $out"
(cd "$here" && JAWATA_DOGFOOD_REPO="$run/jawata-mcp" JAWATA_DOGFOOD_DIST="$dist" JAWATA_DOGFOOD_OUT="$run" \
    xvfb-run -a mvn -B verify -pl releng/org.jawata.eclipse.target,bundles/org.jawata.eclipse,bundles/org.jawata.eclipse.chat,bundles/org.jawata.eclipse.terminal,tests/org.jawata.eclipse.tests -am \
    -Djawata.live=true -Dtest=DogfoodTest -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Djawata.download="$eclipse" -Djawata.profile="epp.package.java" -Djawata.product="org.eclipse.epp.package.java.product") > "$out" 2>&1
rc=$?
grep -h 'jawata-dogfood' "$out" | head -80
summary="$(grep -E '^Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$out" | tail -1)"
if [ "$rc" = 0 ] && echo "$summary" | grep -q '^Tests run: 1, Failures: 0, Errors: 0, Skipped: 0$'; then
    echo "28k-dogfood: PASSED ($summary); report $run/dogfood-report.tsv"
    exit 0
fi
echo "28k-dogfood: FAILED (exit $rc, ${summary:-no test summary}); output: $out"
exit 1
