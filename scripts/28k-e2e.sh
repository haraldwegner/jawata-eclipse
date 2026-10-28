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
# P21 (plan Stage 21): the chat's page (scripts/build-chat-page.sh: the jawata-chat commit in chat.ref, bundled into the chat bundle's
# gitignored page/ folder) is built before mvn, like the engine; this script runs it again and fails the run if it fails.
# Needs: a built update site (scripts/fetch-engine.sh and scripts/build-chat-page.sh, then xvfb-run -a mvn -B verify), xvfb-run, curl, jq, sha512sum, and the engine checkout
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

# P20 (plan Stage 20, spec D9): real Eclipse instances with the plug-in write real notes; studio's compiled code reads them with its own
# reader and the row builder both the list and the tray call (src-tauri/tests/p20_real_eclipse_notes.rs in jawata-studio, the studio
# checkout named by JAWATA_STUDIO). Studio has no command-line door to its dashboard, so the test calls the production functions. Three
# workspaces: C is opened once and closed (studio saw it live, so it stays listed, not running); A and B are open at the end. Expected:
# C alone: 1 running; C closed: 0 running, 1 not running; A, B and C: 2 running, 1 not running. Studio deletes no note and no row has a switch.
p20_start() {  # $1 eclipse dir, $2 workspace dir, $3 notes dir, $4 console fifo, $5 console output, $6 fd number → sets p20_pid
    mkdir -p "$2" "$3"; rm -f "$4"; mkfifo "$4"
    xvfb-run -a "$1/eclipse" -nosplash -data "$2" -consoleLog -console -vmargs "-Djawata.engine.notes.dir=$3" < "$4" > "$5" 2>&1 &
    p20_pid=$!
    eval "exec $6>\"$4\""
}
p20_stop() {  # $1 pid, $2 fd number: a clean stop through the console; the engine deletes its own note
    eval "printf 'close\n' >&$2" 2>/dev/null; sleep 1; eval "printf 'y\n' >&$2" 2>/dev/null
    eval "exec $2>&-"
    for _ in $(seq 1 30); do kill -0 "$1" 2>/dev/null || break; sleep 1; done
    kill -0 "$1" 2>/dev/null && { pkill -P "$1"; kill "$1"; } 2>/dev/null
    wait "$1" 2>/dev/null
}
p20_wait_notes() {  # $1 notes dir, $2 how many notes, within 180 s
    for _ in $(seq 1 36); do
        [ "$(ls "$1"/eclipse-*.json 2>/dev/null | wc -l)" -eq "$2" ] && return 0
        sleep 5
    done
    return 1
}
p20_studio() {  # $1 notes dir, $2 settings dir, $3 expected "running,notrunning" → studio's own test, its output
    studio="${JAWATA_STUDIO:-$here/../jawata-studio}"
    (cd "$studio/src-tauri" && JAWATA_P20_NOTES="$1" JAWATA_P20_SETTINGS="$2" JAWATA_P20_EXPECT="$3" \
        cargo test --offline --test p20_real_eclipse_notes -- --ignored --nocapture 2>&1)
}
p20() {  # $1 package, $2 eclipse dir, $3 run dir
    local pkg="$1" eclipse="$2" run="$3" notes="$3/p20-notes" settings="$3/p20-settings" out pid_c pid_a pid_b
    rm -rf "$notes" "$settings" "$run"/p20-ws-*; mkdir -p "$settings"
    p20_start "$eclipse" "$run/p20-ws-c" "$notes" "$run/p20-c.fifo" "$run/p20-c.out" 5; pid_c=$p20_pid
    p20_wait_notes "$notes" 1 || { fail "P20 $pkg: no note for the first workspace within 180 s; console: $run/p20-c.out"; p20_stop "$pid_c" 5; return; }
    out="$(p20_studio "$notes" "$settings" "1,0")"
    if [ $? -eq 0 ]; then ok "P20 $pkg: one Eclipse open: studio's reader shows 1 running Eclipse workspace, no switch, no note deleted"
    else fail "P20 $pkg: studio's reader did not show 1 running: $(echo "$out" | tail -6 | tr '\n' ' ')"; p20_stop "$pid_c" 5; return; fi
    p20_stop "$pid_c" 5
    out="$(p20_studio "$notes" "$settings" "0,1")"
    if [ $? -eq 0 ]; then ok "P20 $pkg: after that Eclipse closed, studio still lists its workspace, not running (it remembered it)"
    else fail "P20 $pkg: the closed workspace is not listed as not running: $(echo "$out" | tail -6 | tr '\n' ' ')"; return; fi
    p20_start "$eclipse" "$run/p20-ws-a" "$notes" "$run/p20-a.fifo" "$run/p20-a.out" 6; pid_a=$p20_pid
    p20_start "$eclipse" "$run/p20-ws-b" "$notes" "$run/p20-b.fifo" "$run/p20-b.out" 7; pid_b=$p20_pid
    if p20_wait_notes "$notes" 2; then
        out="$(p20_studio "$notes" "$settings" "2,1")"
        if [ $? -eq 0 ]; then ok "P20 $pkg: two Eclipse instances open and one closed: studio answers 2 running, 1 not running, no switch on any, the tray rows are the list's rows ($(echo "$out" | grep -c 'row:') rows)"
        else fail "P20 $pkg: studio's answer is not 2 running, 1 not running: $(echo "$out" | tail -6 | tr '\n' ' ')"; fi
    else
        fail "P20 $pkg: the two Eclipse instances did not both write a note within 180 s; consoles: $run/p20-a.out, $run/p20-b.out"
    fi
    p20_stop "$pid_a" 6; p20_stop "$pid_b" 7
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
# The chat's page, the same way (step 21.1): built from the jawata-chat commit chat.ref names; a failure fails the run.
bash "$here/scripts/build-chat-page.sh" > "$work/build-chat-page.out" 2>&1 || { fail "build-chat-page.sh: $(tail -1 "$work/build-chat-page.out")"; }
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
    (cd "$here" && xvfb-run -a mvn -B verify -pl releng/org.jawata.eclipse.target,bundles/org.jawata.eclipse,bundles/org.jawata.eclipse.chat,tests/org.jawata.eclipse.tests -am \
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
    natures="$(grep -h 'jawata-fixture natures present here' "$tests_out" | head -1 | sed -E 's/^.*jawata-fixture //')"
    want='m2e=true, buildship=true'; [ "$pkg" = rcp ] && want='m2e=true, buildship=true, pde=true'
    case "$natures" in
        *"$want"*) ok "D2 $pkg: the read-only run's Maven, Gradle and plug-in fixtures had their real natures in this download ($natures)" ;;
        *) fail "D2 $pkg: the fixtures did not have the real natures this download should carry (wanted $want; ${natures:-the test printed none})" ;;
    esac
    # P17 (plan Stage 17, spec D5): in this download the preference page lists every setting of the engine, and a value typed on it
    # and saved shows in the next health_check answer of a client that connects with no key (JawataPreferencePageTest). The two tests
    # that prove those two promises must each be in the report and passed: a passed testcase is a self-closed element, a skipped or
    # failed one has a child, so deleting or disabling either test turns this check red instead of leaving a smaller green count.
    prefs="$(grep -h 'Tests run:.*in org.jawata.eclipse.tests.JawataPreferencePageTest' "$tests_out" | head -1)"
    prefs_xml="$here/tests/org.jawata.eclipse.tests/target/surefire-reports/TEST-org.jawata.eclipse.tests.JawataPreferencePageTest.xml"
    prefs_unproved=""
    for proving in aValueTypedOnThePageIsWhatTheEngineReadsAndShowsInAToolAnswer everySettingTheEngineReadsIsOnThePage; do
        grep -q "<testcase name=\"$proving\"[^>]*/>" "$prefs_xml" 2>/dev/null || prefs_unproved="$prefs_unproved $proving"
    done
    case "$prefs" in
        "Tests run: "[0-9]*", Failures: 0, Errors: 0"*)
            if [ -z "$prefs_unproved" ]; then
                ok "P17 $pkg: the preference page shows every setting of the engine, and a value saved on it changes a tool answer for a client with no key, on a page that scrolls (${prefs%%,*}; the two proving tests are in the report and passed)"
            else
                fail "P17 $pkg: the preference page tests ran but these did not pass or are missing:$prefs_unproved"
            fi ;;
        *) fail "P17 $pkg: the preference page tests did not pass in this download (${prefs:-they did not run})" ;;
    esac
    # P18 (plan Stage 18, W3): in this download a rename a client asks for over MCP lands as one step on Edit > Undo and one Undo
    # restores the files, a trial never reaches the undo history, a change over unsaved editor text is refused by name and the text is
    # untouched, and a run of write tools leaves the configuration files alone (EclipseWritesTest)
    writes="$(grep -h 'Tests run:.*in org.jawata.eclipse.tests.EclipseWritesTest' "$tests_out" | head -1)"
    case "$writes" in
        "Tests run: "[0-9]*", Failures: 0, Errors: 0"*) ok "P18 $pkg: a rename is one Undo step and one Undo restores it, a trial is not on the undo history, unsaved editor text is refused by name and untouched, write tools leave the configuration alone (${writes%%,*})" ;;
        *) fail "P18 $pkg: the writes tests did not pass in this download (${writes:-they did not run})" ;;
    esac
    # P19 (plan Stage 19, W4): in this download a program started with Run for jawata runs in Run mode with the debug agent and the
    # recording, on a copy of the person's configuration that is never saved, as a background job; the engine's debugger finds it and
    # attaches by process id; and when the person starts Eclipse's Debug the agent's session ends and its next attach is refused until
    # the person is done (HostRunsTest). The agent starting the program itself through MCP waits for the engine's host-run port (28k-A19-2).
    runs="$(grep -h 'Tests run:.*in org.jawata.eclipse.tests.HostRunsTest' "$tests_out" | head -1)"
    case "$runs" in
        "Tests run: "[0-9]*", Failures: 0, Errors: 0"*) ok "P19 $pkg: Run for jawata starts the program in Run mode with the debug agent on an unsaved copy and the saved file is untouched, the debugger attaches, the person's Debug ends the agent's session and is not shared (${runs%%,*})" ;;
        *) fail "P19 $pkg: the Run for jawata tests did not pass in this download (${runs:-they did not run})" ;;
    esac
    # P21 (plan Stage 21, W6): in this download the chat runs in the SWT Browser page. The chat bundle and its tests are in the build; each
    # class below must have run with no failure, and the node that builds the page is on the PATH. What they prove, class by class:
    #   AgentRelayTest, McpOfferTest         the agent's program is started, its lines relayed, and it is offered the engine's tools
    #   ChatPageTest, ChatBundleManifestTest the page loads with the contract it was built against; the bundle imports only the small access package
    #   ChatHostConformanceTest              chat-core's host-port conformance checks pass on the Eclipse adapter
    #   ChatReplayTest                       the recorded sessions give the view the streams chat-core records
    #   ChatSetupTest, ChatQuestionsTest, WorkbenchDialogsTest   the preference page, the questions and the notices, pressed in real dialogs
    #   ChatCommandsTest                     every chat command is an Eclipse command, and a command gives the page's own stream
    #   ChatThemeTest, ChatThemePageTest     the theme map is pinned input by input, and an open chat follows a change of theme
    #   ChatBusyTest                         a second prompt waits behind a long tool call and a cancel is not held behind it, while the interface and the focus stay as they were
#   ChatPageGuardsTest                   the browser cannot be sent away from the chat, early messages reach the page, the host offers the agent jawata
    p21_missing=""
    for p21_class in AgentRelayTest McpOfferTest ChatPageTest ChatBundleManifestTest ChatHostConformanceTest ChatReplayTest ChatSetupTest \
                     ChatQuestionsTest WorkbenchDialogsTest ChatCommandsTest ChatThemeTest ChatThemePageTest ChatBusyTest ChatPageGuardsTest; do
        p21_line="$(grep -h "Tests run:.*in org.jawata.eclipse.tests.$p21_class\$" "$tests_out" | head -1)"
        case "$p21_line" in
            "Tests run: "[0-9]*", Failures: 0, Errors: 0"*) ;;
            *) p21_missing="$p21_missing $p21_class" ;;
        esac
    done
    if [ -z "$p21_missing" ] && node --version > /dev/null 2>&1; then
        ok "P21 $pkg: the chat bundle and its 14 test classes are in the build and passed in this download: the agent relay, the engine's tools offered to the agent, the page and its contract, the host-port conformance, the replayed sessions, the preference page and dialogs, the Eclipse commands, the theme map and a theme change, and a long tool call that leaves the interface and the focus alone"
    else
        fail "P21 $pkg: the chat tests did not all pass in this download (not passed or not run:${p21_missing:- none}; node: $(node --version 2>&1 | head -1))"
    fi
    # P22 (plan Stage 22, W7, spec D8): in this download File > Import a VS Code workspace reads a .code-workspace file and opens Eclipse's
    # own Smart Import wizard for each folder it names; the person confirming each (the test presses Finish), the projects equal those of
    # an import by hand and jawata creates none itself (ImportCodeWorkspaceTest, 6 tests). The path taken: the signed fallback, the wizard
    # reached by its id in the public registry and pre-filled by name, since Smart Import's classes are in an internal package.
    imports="$(grep -h 'Tests run:.*in org.jawata.eclipse.tests.ImportCodeWorkspaceTest$' "$tests_out" | head -1)"
    case "$imports" in
        "Tests run: 6, Failures: 0, Errors: 0"*) ok "P22 $pkg: the import command on the fixture workspace gives the projects an import by hand gives, through Eclipse's own Smart Import wizard, the person confirming each folder (${imports%%,*}); path taken: the registry wizard, pre-filled by name" ;;
        *) fail "P22 $pkg: the VS Code workspace import tests did not pass in this download (${imports:-they did not run})" ;;
    esac
    # P23 (plan Stage 23, spec D10, his pick of 2026-10-28): in this download the chat is one view that opens where the person chose, in the
    # editor area in the middle or in a side panel, each loading the chat and taking a chat command; the first opening asks once and keeps
    # the answer; a closed chat opens again where it was chosen and an open one stays (ChatPlacementsTest, 10 tests). And the chat reopens
    # the conversation the person had last when they want that, remembering the one a prompt, load or resume belongs to, with both
    # choices on the preference page (ChatLastConversationTest, 5 tests).
    placements="$(grep -h 'Tests run:.*in org.jawata.eclipse.tests.ChatPlacementsTest$' "$tests_out" | head -1)"
    case "$placements" in
        "Tests run: 10, Failures: 0, Errors: 0"*) ok "P23 $pkg: the chat view opens in the middle or in a side panel as chosen, loads and takes a command in each, the real first-run question has two buttons that each choose their place and asks once, a chat closed from the middle opens in a side panel when that is now chosen, a chat that carries the saved mark is not moved, an open chat stays, and the saved layout holds the view with its mark (${placements%%,*})" ;;
        *) fail "P23 $pkg: the chat placement tests did not pass in this download (${placements:-they did not run})" ;;
    esac
    lastconv="$(grep -h 'Tests run:.*in org.jawata.eclipse.tests.ChatLastConversationTest$' "$tests_out" | head -1)"
    case "$lastconv" in
        "Tests run: 5, Failures: 0, Errors: 0"*) ok "P23 $pkg: the chat asks its page to reopen the last conversation when the person wants that, keeps the conversation a prompt, load or resume belongs to, and the preference page saves both choices (${lastconv%%,*})" ;;
        *) fail "P23 $pkg: the last-conversation tests did not pass in this download (${lastconv:-they did not run})" ;;
    esac
    # D6's route subclasses WikiText's editor and registers nothing for .md: Eclipse's log in that test run carries no rejected
    # registration and no exception thrown from jawata's code (a WikiText release changing the editor class shows up here).
    log="$here/tests/org.jawata.eclipse.tests/target/work/data/.metadata/.log"
    # frames of the test bundle itself are not jawata's code: HostPortsTest throws an exception on purpose to prove one is logged
    # and entries that are the Stage 18 tests' deliberate refusals (UNSAVED_IN_EDITOR, CHARSET_NOT_UTF8): the engine's own logger writes
    # an expected refusal into Eclipse's log with its whole stack (ledger 28k-A18-6), and the refusal is the behaviour under test
    noise="$( [ -f "$log" ] && awk 'BEGIN { RS = "!ENTRY" } !/UNSAVED_IN_EDITOR|CHARSET_NOT_UTF8/' "$log" | grep -E 'is ignored|already registered|at org\.jawata\.' | grep -v 'at org\.jawata\.eclipse\.tests\.' | head -3 )"
    if [ -n "$tests_out" ] && grep -q -- "-install $eclipse " "$tests_out" && [ -z "$noise" ]; then
        ok "D6 $pkg: Eclipse's log in the test run has no rejected registration and no exception from jawata's code"
    else
        fail "D6 $pkg: Eclipse's log in the test run: ${noise:-(the tests did not run in this download)}; log: $log"
    fi
    p16 "$pkg" "$eclipse" "$run"
    p20 "$pkg" "$eclipse" "$run"
    # P24 (plan Stage 24, Stage 23's open gate): a REAL restart of this Eclipse keeps the chat where it was. scripts/restart-check.sh opens
    # the chat in the middle, closes Eclipse, starts it again on the same workspace and looks at the screen. Not part of it: the last
    # conversation reopening (that needs a real agent, plan 21.15).
    restart_out="$(bash "$here/scripts/restart-check.sh" "$eclipse" "$run/restart" 2>&1)"; restart_rc=$?
    if [ "$restart_rc" = 0 ]; then ok "P24 $pkg: ${restart_out#restart-check: ok }"; else fail "P24 $pkg: ${restart_out:-the restart check printed nothing}"; fi
done

echo
echo "28k-E2E (jawata-eclipse) passed=$passed failed=$failed"
[ "$failed" = 0 ]
