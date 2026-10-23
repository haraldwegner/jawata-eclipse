#!/usr/bin/env bash
# Sprint 28k end-to-end promises for the plug-in repository.
#
# P13: the built update site installs into clean Eclipse 4.41 (2026-09) Java and RCP downloads (Linux,
# p2 director), and Eclipse started from each, in a virtual screen, runs with the plug-in present: the
# workbench bundle reaches ACTIVE, both org.jawata bundles are resolved, and so are the two Eclipse bundles
# spec D6 and D7 stand on (WikiText's Markdown editor, the Terminal's local shell) — the PDE tests prove
# their behaviour INSIDE each download: after the start check, the PDE tests run in that same installation (Tycho's p2Installed
# mode adds only the test bundle and its harness), so the preview and the terminal are proven where a user installs the plug-in.
# JAWATA_SPEC=<a spec .md> also opens a real spec (the specs are not in this repository).
# R8: the update site ships none of JDT, LTK, SWT, JFace, an slf4j provider, org.jawata.jdtpatch or
# org.jawata.resident (contract §16 row 1). R9, engine side: no jawata-mcp build file names this repository.
# The commit hook: installed in this clone, and its own test passes.
#
# Needs: a built update site (xvfb-run -a mvn -B verify), xvfb-run, curl, sha512sum, and the engine checkout
# with its dist built (it runs the engine's promises first).
# Downloads are kept in .work/dl-<package>/ and checked against eclipse.org's sha512; every run unpacks a
# fresh copy into .work/run-<package>/, so an earlier run's install never counts.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
work="$here/.work"
site="$here/repository/target/repository"
release="https://download.eclipse.org/releases/2026-09"
epp="https://download.eclipse.org/technology/epp/downloads/release/2026-09/R"
feature="org.jawata.eclipse.feature.feature.group"
passed=0 failed=0

ok()   { echo "  ok    $*"; passed=$((passed + 1)); }
fail() { echo "  FAIL  $*"; failed=$((failed + 1)); }

[ -f "$site/content.jar" ] || { echo "no update site at $site — run: xvfb-run -a mvn -B verify"; exit 2; }
command -v xvfb-run >/dev/null || { echo "xvfb-run is not installed"; exit 2; }

# The engine's promises run first (plan §5, the E2E gate): a promise once added is never removed, and the
# plug-in's promises stand on the engine's. JAWATA_MCP names the engine checkout; its dist must be built.
engine="${JAWATA_MCP:-$here/../jawata-mcp}"
echo "engine promises ($engine/build/28k-e2e.sh)"
if (cd "$engine" && bash build/28k-e2e.sh); then
    ok "the engine's promises hold (its own lines above)"
else
    fail "the engine's promises: $engine/build/28k-e2e.sh failed (its own lines above)"
fi

fetch() {  # $1 package → path of the verified archive
    local name="eclipse-$1-2026-09-R-linux-gtk-x86_64.tar.gz" dir="$work/dl-$1"
    mkdir -p "$dir"
    if [ ! -f "$dir/$name" ]; then
        curl -sSfL -o "$dir/$name.part" "$epp/$name" && mv "$dir/$name.part" "$dir/$name" || return 1
    fi
    curl -sSfL -o "$dir/$name.sha512" "$epp/$name.sha512" || return 1
    (cd "$dir" && echo "$(cut -d' ' -f1 "$name.sha512")  $name" | sha512sum -c --quiet) || return 1
    echo "$dir/$name"
}

starts_with_plugin() {  # $1 eclipse dir, $2 run dir
    local eclipse="$1" run="$2" fifo="$2/console.in" out="$2/console.out"
    mkfifo "$fifo"
    xvfb-run -a "$eclipse/eclipse" -nosplash -data "$run/workspace" -consoleLog -console \
        < "$fifo" > "$out" 2>&1 &
    local pid=$!
    exec 3>"$fifo"
    local up=0
    for _ in $(seq 1 36); do
        printf 'ss org.eclipse.ui.workbench\n' >&3 2>/dev/null || break
        sleep 5
        if grep -qE 'ACTIVE +org\.eclipse\.ui\.workbench_' "$out"; then up=1; break; fi
    done
    printf 'ss org.jawata\nss org.eclipse.mylyn.wikitext.markdown.ui\nss org.eclipse.terminal.connector.local\n' >&3 2>/dev/null; sleep 3
    printf 'close\n' >&3 2>/dev/null; sleep 1; printf 'y\n' >&3 2>/dev/null
    exec 3>&-
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && { pkill -P "$pid"; kill "$pid"; } 2>/dev/null
    wait "$pid" 2>/dev/null
    [ "$up" = 1 ] || { echo "    the workbench never reached ACTIVE (180 s); console: $out"; return 1; }
    local b
    for b in org.jawata.eclipse org.jawata.eclipse.chat org.eclipse.mylyn.wikitext.markdown.ui org.eclipse.terminal.connector.local; do
        grep -qE "(RESOLVED|STARTING|ACTIVE) +${b//./\\.}_" "$out" \
            || { echo "    $b is not resolved in the running Eclipse; console: $out"; return 1; }
    done
}

echo "R8, R9 and the commit hook"
shipped="$(ls "$site/plugins" | sed 's/_[0-9].*//')"
forbidden='^(org\.eclipse\.(jdt|ltk|swt|jface)|org\.jawata\.(jdtpatch|resident)|slf4j\.(simple|nop|jdk14|reload4j)|ch\.qos\.logback)'
bad="$(printf '%s\n' "$shipped" | grep -E "$forbidden")"
if [ -z "$bad" ]; then
    ok "R8: the update site ships $(printf '%s\n' "$shipped" | wc -l) bundles, none of JDT, LTK, SWT, JFace, an slf4j provider, jdtpatch or resident"
else
    fail "R8: the update site ships $(echo $bad)"
fi
named="$(cd "$engine" && git ls-files | grep -E '(^|/)(pom\.xml|MANIFEST\.MF|feature\.xml)$|\.target$' | xargs grep -l 'org\.jawata\.eclipse' 2>/dev/null)"
if [ -z "$named" ]; then ok "R9: no jawata-mcp build file names org.jawata.eclipse"; else fail "R9: jawata-mcp build files name it: $(echo $named)"; fi
if cmp -s "$here/scripts/commit-msg" "$here/.git/hooks/commit-msg" && sh "$here/scripts/test-commit-hook.sh" > /dev/null 2>&1; then
    ok "the commit hook is installed in this clone and its test passes"
else
    fail "the commit hook is not installed (scripts/install-hooks.sh) or its test fails (scripts/test-commit-hook.sh)"
fi

for pkg in java rcp; do
    echo "P13 $pkg package"
    archive="$(fetch "$pkg")" || { fail "P13 $pkg: download or checksum failed"; continue; }
    run="$work/run-$pkg"
    rm -rf "$run" && mkdir -p "$run"
    tar -xzf "$archive" -C "$run" || { fail "P13 $pkg: could not unpack $archive"; continue; }
    eclipse="$run/eclipse"
    if "$eclipse/eclipse" -nosplash -application org.eclipse.equinox.p2.director \
            -repository "file:$site,$release" -installIU "$feature" > "$run/director.out" 2>&1 \
       && "$eclipse/eclipse" -nosplash -application org.eclipse.equinox.p2.director \
            -listInstalledRoots 2>/dev/null | grep -q "^${feature//./\\.}/"; then
        ok "P13 $pkg: the update site installs (director), $feature is an installed root"
    else
        fail "P13 $pkg: the update site did not install; director output: $run/director.out"
        continue
    fi
    if starts_with_plugin "$eclipse" "$run"; then
        ok "P13 $pkg: Eclipse starts in Xvfb, the workbench is ACTIVE; both org.jawata bundles, WikiText's Markdown editor and the Terminal's local shell are resolved"
    else
        fail "P13 $pkg: Eclipse did not start with the plug-in present"
    fi
    # D6/D7 inside this download: Tycho installs the test bundle and its harness on top of the installation above (p2 director, the
    # download's own profile) and runs the PDE tests in it; the components under test stay the download's own bundles.
    tests_out="$run/pde-tests.out"
    (cd "$here" && xvfb-run -a mvn -B verify -pl releng/org.jawata.eclipse.target,tests/org.jawata.eclipse.tests -am \
        -Djawata.download="$eclipse" -Djawata.profile="epp.package.$pkg" -Djawata.product="org.eclipse.epp.package.$pkg.product" \
        ${JAWATA_SPEC:+-Djawata.spec.md="$JAWATA_SPEC"}) > "$tests_out" 2>&1
    tests_rc=$?
    summary="$(grep -E '^Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$tests_out" | tail -1)"
    ran="$(echo "$summary" | sed -E 's/^Tests run: ([0-9]+).*Skipped: ([0-9]+)$/\1 \2/')"
    if [ "$tests_rc" = 0 ] && grep -q -- "-install $eclipse " "$tests_out" \
       && [ -n "$ran" ] && [ $(( ${ran% *} - ${ran#* } )) -ge 2 ] && echo "$summary" | grep -q 'Failures: 0, Errors: 0'; then
        ok "D6/D7 $pkg: the PDE tests ran inside this download ($summary): a spec opens with source and preview, the Terminal runs bash"
    else
        fail "D6/D7 $pkg: the PDE tests did not pass inside this download (exit $tests_rc, ${summary:-no test summary}); output: $tests_out"
    fi
done

echo
echo "28k-E2E (jawata-eclipse) passed=$passed failed=$failed"
[ "$failed" = 0 ]
