#!/usr/bin/env bash
# Sprint 28k end-to-end promises for the plug-in repository.
#
# P13: the built update site installs into clean Eclipse 4.41 (2026-09) Java and RCP downloads (Linux,
# p2 director), and Eclipse started from each, in a virtual screen, runs with the plug-in present: the
# workbench bundle reaches ACTIVE, both org.jawata bundles are resolved, and so are the two Eclipse bundles
# spec D6 and D7 stand on (WikiText's Markdown editor, the Terminal's local shell) — the PDE tests prove
# their behaviour INSIDE each download: after the start check, the PDE tests run in that same installation (Tycho's p2Installed
# mode adds only the test bundle and its harness), so the preview and the terminal are proven where a user installs the plug-in.
# P15 (plan Stage 15): in the same started Eclipse the released engine's bundles, and the two libraries they bring (H2, Jackson), are
# resolved too; the update site ships each of them byte-identical to the same-named file of the release in engine.version.
# JAWATA_SPEC=<a spec .md> also opens a real spec (the specs are not in this repository).
# R8: the update site ships none of JDT, LTK, SWT, JFace, an slf4j provider, org.jawata.jdtpatch or
# org.jawata.resident (contract §16 row 1). R9, engine side: no jawata-mcp build file names this repository.
# The commit hook: installed in this clone, and its own test passes.
#
# Needs: a built update site (scripts/fetch-engine.sh, then xvfb-run -a mvn -B verify), xvfb-run, curl, jq, sha512sum, and the engine checkout
# with its dist built (it runs the engine's promises first).
# Downloads are kept in .work/dl-<package>/ and checked against eclipse.org's sha512; every run unpacks a
# fresh copy into .work/run-<package>/, so an earlier run's install never counts.
set -uo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
work="$here/.work"
site="${JAWATA_SITE:-$here/repository/target/repository}"   # JAWATA_SITE: a tampered copy, for the gates' red controls
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
    printf 'ss org.jawata\nss com.h2database\nss com.fasterxml.jackson.core\nss org.eclipse.mylyn.wikitext.markdown.ui\nss org.eclipse.terminal.connector.local\n' >&3 2>/dev/null; sleep 3
    printf 'close\n' >&3 2>/dev/null; sleep 1; printf 'y\n' >&3 2>/dev/null
    exec 3>&-
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && { pkill -P "$pid"; kill "$pid"; } 2>/dev/null
    wait "$pid" 2>/dev/null
    [ "$up" = 1 ] || { echo "    the workbench never reached ACTIVE (180 s); console: $out"; return 1; }
    local b
    # P15: the released engine's bundles and the two libraries they bring resolve on THIS Eclipse's own JDT, LTK and JaCoCo.
    for b in org.jawata.eclipse org.jawata.eclipse.chat org.jawata.spi org.jawata.core org.jawata.mcp org.jawata.catalogue \
             com.h2database com.fasterxml.jackson.core.jackson-core com.fasterxml.jackson.core.jackson-databind \
             com.fasterxml.jackson.core.jackson-annotations org.eclipse.mylyn.wikitext.markdown.ui org.eclipse.terminal.connector.local; do
        grep -qE "(RESOLVED|STARTING|ACTIVE) +${b//./\\.}_" "$out" \
            || { echo "    $b is not resolved in the running Eclipse; console: $out"; return 1; }
    done
}

# P16 (plan Stage 16): Eclipse with the plug-in on a fixture workspace. The engine starts with the workbench and writes its note; an
# MCP client at the note's endpoint, with no key, gets the same tool names as the headless engine's golden (R7) and HOST_OWNS_WORKSPACE
# from load_project; a clean stop removes the note; relaunched on a second workspace the note names that workspace and the engine
# answers at its own address. $engine is the engine checkout, whose build/28k-golden-tools.json is the headless surface.
p16_session=""
p16_mcp() {  # $1 url, $2 JSON body → the JSON answer (a bare object, or the data: frame of an event stream)
    curl -sS -m 120 -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        ${p16_session:+-H "Mcp-Session-Id: $p16_session"} -d "$2" "$1" | sed -E 's/^data: ?//' | grep -v '^event:' | head -1
}
p16_start() {  # $1 eclipse dir, $2 workspace dir, $3 notes dir, $4 console fifo, $5 console output → sets p16_pid; fd 4 is the console
    mkdir -p "$2" "$3"; rm -f "$4"; mkfifo "$4"
    xvfb-run -a "$1/eclipse" -nosplash -data "$2" -consoleLog -console -vmargs "-Djawata.engine.notes.dir=$3" < "$4" > "$5" 2>&1 &
    p16_pid=$!
    exec 4>"$4"
}
p16_stop() {  # a clean stop through the console, as a person closing Eclipse would: the note is deleted by it
    printf 'close\n' >&4 2>/dev/null; sleep 1; printf 'y\n' >&4 2>/dev/null
    exec 4>&-
    for _ in $(seq 1 30); do kill -0 "$p16_pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$p16_pid" 2>/dev/null && { pkill -P "$p16_pid"; kill "$p16_pid"; } 2>/dev/null
    wait "$p16_pid" 2>/dev/null
}
p16_note() {  # $1 notes dir → the path of the one eclipse note, within 180 s
    local n
    for _ in $(seq 1 36); do
        n="$(ls "$1"/eclipse-*.json 2>/dev/null | head -1)"
        [ -n "$n" ] && { echo "$n"; return 0; }
        sleep 5
    done
    return 1
}
p16() {  # $1 package, $2 eclipse dir, $3 run dir
    local pkg="$1" eclipse="$2" run="$3" notes="$3/p16-notes" ws1="$3/p16-ws-one" ws2="$3/p16-ws-two" note url names health refused
    rm -rf "$notes" "$ws1" "$ws2"
    echo "P16 $pkg"
    p16_start "$eclipse" "$ws1" "$notes" "$run/p16.in" "$run/p16-one.out"
    note="$(p16_note "$notes")" || { fail "P16 $pkg: no engine note appeared within 180 s; console: $run/p16-one.out"; p16_stop; return; }
    ok "P16 $pkg: the engine started with the workbench and wrote its note ($(basename "$note"))"
    url="$(jq -r .endpoint "$note")"
    if [ "$(jq -r .hostKind "$note")" = eclipse ] && [ "$(jq -r 'has("key")' "$note")" = false ] && [ "$(jq -r .workspacePath "$note")" = "$ws1" ]; then
        ok "P16 $pkg: the note says hostKind eclipse, names this workspace, and carries no key"
    else
        fail "P16 $pkg: the note is not an Eclipse note for $ws1 without a key: $(head -c 300 "$note")"
    fi
    p16_session=""
    curl -sS -m 60 -D "$run/p16.hdr" -o /dev/null -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"p16","version":"1"}}}' "$url"
    p16_session="$(grep -i '^mcp-session-id:' "$run/p16.hdr" | tr -d '\r' | cut -d' ' -f2)"
    names=""
    for _ in $(seq 1 60); do   # the Java provider binds a moment after the engine starts
        names="$(p16_mcp "$url" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq -r '[.result.tools[].name] | sort | join(" ")' 2>/dev/null)"
        case " $names " in *" search_symbols "*) break ;; esac
        sleep 2
    done
    # the golden of the release the plug-in ships (engine.version), not of whatever the engine checkout has moved on to
    golden="$(git -C "$engine" show "v$(cat "$here/engine.version"):build/28k-golden-tools.json" | jq -r '[.[].name] | sort | join(" ")')"
    if [ -n "$golden" ] && [ "$names" = "$golden" ]; then
        ok "P16 $pkg: a client at the note's endpoint with no key lists $(echo $names | wc -w) tools, the same names as the headless golden of engine $(cat "$here/engine.version") (R7)"
    else
        fail "P16 $pkg: the tools listed in Eclipse differ from the headless golden: ${names:-none}"
    fi
    health="$(p16_mcp "$url" '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"health_check","arguments":{}}}' | jq -r '.result.content[0].text | fromjson | .data.toolCount' 2>/dev/null)"
    [ "$health" = "$(echo $names | wc -w)" ] && ok "P16 $pkg: health_check answers toolCount $health" || fail "P16 $pkg: health_check toolCount ${health:-none}, tools listed $(echo $names | wc -w)"
    refused="$(p16_mcp "$url" '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"load_project","arguments":{"projectPath":"/tmp"}}}')"
    added="$(p16_mcp "$url" '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"project","arguments":{"action":"add","projectPath":"/tmp"}}}')"
    case "$refused$added" in *HOST_OWNS_WORKSPACE*HOST_OWNS_WORKSPACE*) ok "P16 $pkg: load_project and project add both answer HOST_OWNS_WORKSPACE" ;; *) fail "P16 $pkg: the project tools did not answer HOST_OWNS_WORKSPACE: load_project $(echo "$refused" | head -c 200); project add $(echo "$added" | head -c 200)" ;; esac
    p16_stop
    [ -z "$(ls "$notes"/eclipse-*.json 2>/dev/null)" ] && ok "P16 $pkg: a clean stop deleted the note" || fail "P16 $pkg: the note is still there after a clean stop"
    # A second workspace: the note moves to the new workspace and the engine answers at its own address, never the first one's.
    p16_start "$eclipse" "$ws2" "$notes" "$run/p16.in" "$run/p16-two.out"
    note="$(p16_note "$notes")" || { fail "P16 $pkg: no note for the second workspace; console: $run/p16-two.out"; p16_stop; return; }
    url="$(jq -r .endpoint "$note")"
    p16_session=""
    curl -sS -m 60 -D "$run/p16.hdr" -o /dev/null -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"p16","version":"1"}}}' "$url"
    p16_session="$(grep -i '^mcp-session-id:' "$run/p16.hdr" | tr -d '\r' | cut -d' ' -f2)"
    health="$(p16_mcp "$url" '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"health_check","arguments":{}}}' | jq -r '.result.content[0].text | fromjson | .data.toolCount' 2>/dev/null)"
    if [ "$(jq -r .workspacePath "$note")" = "$ws2" ] && [ -n "$health" ] && [ "$health" != null ]; then
        ok "P16 $pkg: relaunched on a second workspace the note names that workspace and its engine answers (toolCount $health)"
    else
        fail "P16 $pkg: the second launch's note names $(jq -r .workspacePath "$note"), health_check ${health:-none}"
    fi
    p16_stop
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
# R9 from this side, and Stage 15's byte identity: every engine bundle and library the update site ships is, byte for byte, a file of the
# released dist named by engine.version (fetched and checked by scripts/fetch-engine.sh); nothing of the engine is built here.
bash "$here/scripts/fetch-engine.sh" > "$work/fetch-engine.out" 2>&1 || { fail "fetch-engine.sh: $(tail -1 "$work/fetch-engine.out")"; }
. "$here/scripts/lib-bundles.sh"
# The released dist's files by symbolic name: the same bundle must be the same bytes, not merely some released file.
declare -A released
for jar in "$work"/engine-download/unpacked/*/bundles/*.jar; do
    [ -f "$jar" ] && released["$(bundle_symbolic_name "$jar")"]="$(sha256sum "$jar" | cut -d' ' -f1)"
done
# What the engine feature names is the one list (features/org.jawata.engine.feature/feature.xml): the site must ship exactly those, and the
# four engine bundles are a floor, so an engine feature that named nothing could not pass.
engine_ids="$(grep -o '<plugin id="[^"]*"' "$here/features/org.jawata.engine.feature/feature.xml" | sed -E 's/<plugin id="([^"]*)"/\1/')"
engine_expected="$(printf '%s\n' "$engine_ids" | grep -c .)"
floor_missing=""
for need in org.jawata.spi org.jawata.core org.jawata.mcp org.jawata.catalogue; do
    printf '%s\n' "$engine_ids" | grep -qx "$need" || floor_missing="$floor_missing $need"
done
engine_count=0 not_released=""
for id in $engine_ids; do
    f="$(ls "$site/plugins" | grep -E "^${id//./\\.}_[0-9]" | head -1)"
    [ -n "$f" ] || { not_released="$not_released $id(not shipped)"; continue; }
    engine_count=$((engine_count + 1))
    [ "$(sha256sum "$site/plugins/$f" | cut -d' ' -f1)" = "${released[$id]:-none}" ] || not_released="$not_released $f"
done
if [ -z "$not_released" ] && [ -z "$floor_missing" ] && [ "$engine_count" -eq "$engine_expected" ] && [ "$engine_count" -ge 4 ]; then
    ok "P15/R9: the update site ships the $engine_count engine bundles and libraries the engine feature names, each byte-identical to the file of the same name in the released engine $(cat "$here/engine.version")"
else
    fail "P15/R9: shipped engine files ($engine_count of the $engine_expected the engine feature names) not all byte-identical to the release:${not_released:- none listed}${floor_missing:+; the engine feature lacks$floor_missing}"
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
        # the same start check also required the engine bundles (starts_with_plugin): print what it proved, under the promise's own name
        ok "P15 $pkg: in that started Eclipse the eight engine bundles and libraries (spi, core, mcp, catalogue, H2, Jackson x3) are resolved on its own JDT, LTK and JaCoCo"
    else
        fail "P13 $pkg: Eclipse did not start with the plug-in present"
    fi
    # D6/D7 inside this download: Tycho installs the test bundle and its harness on top of the installation above (p2 director, the
    # download's own profile) and runs the PDE tests in it; the components under test stay the download's own bundles.
    tests_out="$run/pde-tests.out"
    (cd "$here" && xvfb-run -a mvn -B verify -pl releng/org.jawata.eclipse.target,bundles/org.jawata.eclipse,tests/org.jawata.eclipse.tests -am \
        -Djawata.download="$eclipse" -Djawata.profile="epp.package.$pkg" -Djawata.product="org.eclipse.epp.package.$pkg.product" \
        ${JAWATA_SPEC:+-Djawata.spec.md="$JAWATA_SPEC"}) > "$tests_out" 2>&1
    tests_rc=$?
    summary="$(grep -E '^Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$tests_out" | tail -1)"
    ran="$(echo "$summary" | sed -E 's/^Tests run: ([0-9]+).*Skipped: ([0-9]+)$/\1 \2/')"
    if [ "$tests_rc" = 0 ] && grep -q -- "-install $eclipse " "$tests_out" \
       && [ -n "$ran" ] && [ $(( ${ran% *} - ${ran#* } )) -ge 2 ] && echo "$summary" | grep -q 'Failures: 0, Errors: 0'; then
        ok "D6/D7 $pkg: the PDE tests ran inside this download ($summary): a .md opens in jawata's editor with source and a preview showing every table, the Terminal runs bash"
    else
        fail "D6/D7 $pkg: the PDE tests did not pass inside this download (exit $tests_rc, ${summary:-no test summary}); output: $tests_out"
    fi
    # Stage 16's snapshot test attaches the m2e, Buildship and PDE natures only when the running Eclipse has them; say which it was here
    echo "  info  D2 $pkg: $(grep -h 'jawata-fixture natures present here' "$tests_out" | head -1 | sed -E 's/^.*jawata-fixture //')"
    # D6's route subclasses WikiText's editor and registers nothing for .md: Eclipse's log in that test run carries no rejected
    # registration and no exception thrown from jawata's code (a WikiText release changing the editor class shows up here).
    log="$here/tests/org.jawata.eclipse.tests/target/work/data/.metadata/.log"
    # frames of the test bundle itself are not jawata's code: HostPortsTest throws an exception on purpose to prove one is logged
    noise="$( [ -f "$log" ] && grep -E 'is ignored|already registered|at org\.jawata\.' "$log" | grep -v 'at org\.jawata\.eclipse\.tests\.' | head -3 )"
    if [ -n "$tests_out" ] && grep -q -- "-install $eclipse " "$tests_out" && [ -z "$noise" ]; then
        ok "D6 $pkg: Eclipse's log in the test run has no rejected registration and no exception from jawata's code"
    else
        fail "D6 $pkg: Eclipse's log in the test run: ${noise:-(the tests did not run in this download)}; log: $log"
    fi
    p16 "$pkg" "$eclipse" "$run"
done

echo
echo "28k-E2E (jawata-eclipse) passed=$passed failed=$failed"
[ "$failed" = 0 ]
