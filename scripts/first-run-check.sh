#!/usr/bin/env bash
# Plan Stage 24 (spec D10, "ask for the preference at the first startup", built as: the first time the chat opens): the question
# "Where should the jawata chat open?" is really on screen the first time the person opens the chat, and a chat that already has a
# stored choice does not ask.
#
# Usage: scripts/first-run-check.sh <eclipse dir with the plug-in installed> <empty work dir>
#
# Run 1: fresh workspace, no choice stored. The chat is opened through Quick Access (Ctrl+3, "jawata chat"); a window titled
# "Where should the jawata chat open?" must appear; Enter presses its first button ("In the middle"); the chat must then be on screen and
# the preference file must hold placement=editor. Run 2 (the red control): the same workspace now has a stored choice; opening the chat
# must NOT show the question. The chat page is recognised by the colour of its Connect button, as in scripts/restart-check.sh.
#
# Needs: Xvfb, xdotool, ImageMagick (convert, import). Exit 0 when it shows what it must, 1 when it does not, 2 when the tools to look at
# the screen are missing (nothing is claimed in that case).
set -uo pipefail

eclipse="${1:?the Eclipse directory}"
work="${2:?an empty work directory}"
blue='srgb(53,132,228)'
title='Where should the jawata chat open?'

for tool in Xvfb xdotool convert import; do
    command -v "$tool" >/dev/null || { echo "first-run-check: $tool is missing; nothing was checked"; exit 2; }
done

settings="$work/ws/.metadata/.plugins/org.eclipse.core.runtime/.settings"
mkdir -p "$settings" "$work/shots"
printf 'eclipse.preferences.version=1\nshowIntro=false\n' > "$settings/org.eclipse.ui.prefs"

display=""
for n in $(seq 90 120); do
    [ -e "/tmp/.X11-unix/X$n" ] || { display=":$n"; break; }
done
[ -n "$display" ] || { echo "first-run-check: no free X display between :90 and :120"; exit 2; }
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

chat_pixels() {  # $1 picture
    convert "$1" -fill black +opaque "$blue" -fill white -opaque "$blue" -format '%[fx:round(mean*w*h)]' info:
}

open_chat() {  # $1 name of the run → starts Eclipse, opens the chat through Quick Access, leaves Eclipse running
    "$eclipse/eclipse" -nosplash -data "$work/ws" -consoleLog > "$work/$1.out" 2>&1 &
    eclipse_pid=$!
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
    sleep 6
}

stop_eclipse() {
    xdotool key alt+f
    sleep 1
    xdotool key x
    for _ in $(seq 1 40); do kill -0 "$eclipse_pid" 2>/dev/null || break; sleep 1; done
    kill "$eclipse_pid" 2>/dev/null
    eclipse_pid=""
}

# run 1: nothing stored, so the question must be on screen
open_chat run1
if ! xdotool search --onlyvisible --name "$title" >/dev/null 2>&1; then
    import -window root "$work/shots/run1-no-question.png"
    echo "first-run-check: FAIL the first time the chat was opened no window titled \"$title\" was on screen (picture $work/shots/run1-no-question.png)"
    exit 1
fi
import -window root "$work/shots/run1-question.png"
xdotool key Return
sleep 8
import -window root "$work/shots/run1-after-answer.png"
if [ "$(chat_pixels "$work/shots/run1-after-answer.png")" -le 0 ]; then
    echo "first-run-check: FAIL after the question was answered the chat was not on screen (picture $work/shots/run1-after-answer.png)"
    exit 1
fi
stop_eclipse
if ! grep -q '^placement=editor$' "$settings/org.jawata.eclipse.chat.prefs" 2>/dev/null; then
    echo "first-run-check: FAIL the answer \"In the middle\" was not kept as placement=editor in $settings/org.jawata.eclipse.chat.prefs"
    exit 1
fi

# run 2: the choice is stored, so the question must not come back
open_chat run2
import -window root "$work/shots/run2.png"
if xdotool search --onlyvisible --name "$title" >/dev/null 2>&1; then
    echo "first-run-check: FAIL the question was asked again although the choice was stored (picture $work/shots/run2.png)"
    exit 1
fi
if [ "$(chat_pixels "$work/shots/run2.png")" -le 0 ]; then
    echo "first-run-check: FAIL with a stored choice the chat was not on screen after it was opened (picture $work/shots/run2.png)"
    exit 1
fi
echo "first-run-check: ok the first time the chat was opened the window \"$title\" was on screen, its first button put the chat in the middle and was kept as placement=editor, and a second opening with the choice stored did not ask again"
exit 0
