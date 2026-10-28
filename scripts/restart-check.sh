#!/usr/bin/env bash
# Plan Stage 24 (spec D10, "restart-restore measured"): a REAL restart of Eclipse keeps the chat where it was.
#
# Usage: scripts/restart-check.sh <eclipse dir with the plug-in installed> <empty work dir>
#
# Session 1: Eclipse starts on a fresh workspace whose preference says the chat opens in the middle; the script opens the chat through
# Quick Access (Ctrl+3, "jawata chat"), looks at the screen, and closes Eclipse with File > Exit. The layout file Eclipse wrote on exit must
# carry the chat view and its placed mark. Session 2: Eclipse starts on the same workspace; the chat must show on the screen, in the middle,
# without anyone opening it.
#
# "The chat shows" is read from the screen: the page's Connect button has one colour (53,132,228) and nothing else in a fresh Eclipse
# has it, so a count of those pixels above zero means the chat page is on screen. If the page's theme changes that colour, this check goes
# red and says so; it does not pass by default.
#
# Eclipse itself opens its Sponsor and Install Java pages in a maximized editor a few seconds after it starts, which hides every view. So
# session 2 takes a picture every two seconds for a minute and passes when the chat is on screen in any of them.
#
# Needs: Xvfb, xdotool, ImageMagick (convert, import). Exit 0 when both sessions show what they must, 1 when they do not, 2 when the tools
# to look at the screen are missing (nothing is claimed in that case).
set -uo pipefail

eclipse="${1:?the Eclipse directory}"
work="${2:?an empty work directory}"
blue='srgb(53,132,228)'

for tool in Xvfb xdotool convert import; do
    command -v "$tool" >/dev/null || { echo "restart-check: $tool is missing; nothing was checked"; exit 2; }
done

mkdir -p "$work/ws/.metadata/.plugins/org.eclipse.core.runtime/.settings" "$work/shots"
settings="$work/ws/.metadata/.plugins/org.eclipse.core.runtime/.settings"
printf 'eclipse.preferences.version=1\nplacement=%s\n' "${RESTART_CHECK_PLACEMENT:-editor}" > "$settings/org.jawata.eclipse.chat.prefs"
printf 'eclipse.preferences.version=1\nshowIntro=false\n' > "$settings/org.eclipse.ui.prefs"

display=""
for n in $(seq 90 120); do
    [ -e "/tmp/.X11-unix/X$n" ] || { display=":$n"; break; }
done
[ -n "$display" ] || { echo "restart-check: no free X display between :90 and :120"; exit 2; }
Xvfb "$display" -screen 0 1600x1000x24 > "$work/xvfb.out" 2>&1 &
xvfb=$!
eclipse_pid=""
cleanup() {
    [ -n "$eclipse_pid" ] && kill "$eclipse_pid" 2>/dev/null
    kill "$xvfb" 2>/dev/null
}
trap cleanup EXIT
sleep 2
export DISPLAY="$display"

chat_pixels() {  # $1 picture → how many pixels have the Connect button's colour
    convert "$1" -fill black +opaque "$blue" -fill white -opaque "$blue" -format '%[fx:round(mean*w*h)]' info:
}

start_eclipse() {  # $1 log
    "$eclipse/eclipse" -nosplash -data "$work/ws" -consoleLog > "$1" 2>&1 &
    eclipse_pid=$!
}

# session 1: open the chat, close Eclipse
start_eclipse "$work/session1.out"
for _ in $(seq 1 60); do
    xdotool search --onlyvisible --name 'Eclipse' >/dev/null 2>&1 && break
    sleep 2
done
sleep 30
xdotool mousemove 500 400 click 1
sleep 1
xdotool key ctrl+3
sleep 2
xdotool type --delay 80 "jawata chat"
sleep 3
xdotool key Return
sleep 8
import -window root "$work/shots/session1-opened.png"
opened="$(chat_pixels "$work/shots/session1-opened.png")"
xdotool key alt+f
sleep 1
xdotool key x
for _ in $(seq 1 40); do kill -0 "$eclipse_pid" 2>/dev/null || break; sleep 1; done
if kill -0 "$eclipse_pid" 2>/dev/null; then
    echo "restart-check: FAIL Eclipse did not close through File > Exit within 40 s (session 1)"
    exit 1
fi
eclipse_pid=""

layout="$work/ws/.metadata/.plugins/org.eclipse.e4.workbench/workbench.xmi"
if [ "$opened" -le 0 ]; then
    echo "restart-check: FAIL the chat was not on screen after it was opened (session 1; picture $work/shots/session1-opened.png)"
    exit 1
fi
if ! grep -q 'key="org.jawata.eclipse.chat.placed" value="yes"' "$layout" 2>/dev/null; then
    echo "restart-check: FAIL the layout Eclipse wrote on exit does not carry the chat's placed mark ($layout)"
    exit 1
fi
if ! grep -A6 'xsi:type="advanced:Area".*elementId="org.eclipse.ui.editorss"' "$layout" | grep -q 'org.jawata.eclipse.chat.view'; then
    echo "restart-check: FAIL the layout Eclipse wrote on exit does not hold the chat view in the editor area ($layout)"
    exit 1
fi

# session 2: nobody opens the chat; it must be on screen anyway
start_eclipse "$work/session2.out"
seen=0
for t in $(seq 1 30); do
    sleep 2
    import -window root "$work/shots/session2-$t.png" 2>/dev/null || continue
    if [ "$(chat_pixels "$work/shots/session2-$t.png")" -gt 0 ]; then seen=$t; break; fi
done
if [ "$seen" -eq 0 ]; then
    echo "restart-check: FAIL after the restart the chat was not on screen in any of 30 pictures over a minute ($work/shots/session2-*.png)"
    exit 1
fi
echo "restart-check: ok the chat was opened in the middle, the layout Eclipse wrote on exit holds the view and its mark, and after a real restart the chat was on screen without anyone opening it (picture $seen of session 2, $((seen * 2)) s after the start)"
exit 0
