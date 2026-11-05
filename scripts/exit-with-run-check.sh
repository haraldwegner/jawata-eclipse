#!/usr/bin/env bash
# Plan 25a.2 (A19-6): quitting Eclipse with a program started by "Run for jawata" running leaves no program behind.
#
# Usage: scripts/exit-with-run-check.sh <eclipse dir with the plug-in installed> <empty work dir>
#
# "Run for jawata" starts the person's run configuration in a launch Eclipse's launch manager does not know (so the debug UI makes no Console
# and takes no focus: ledger 28k-A19-6), which means Eclipse's own shutdown does not stop it. The promise: when the person quits Eclipse the
# program is gone, not left running with nobody to stop it. PDE tests cannot check this, because quitting Eclipse ends the test run, so the
# check is a script that lives outside the Eclipse it quits.
#
# The script makes a fixture program that writes its process id to a file and then runs until stopped, and a run configuration for it (a local
# one, in the workspace's launch folder). It starts Eclipse in Xvfb, chooses Run > Run for jawata > <the configuration> from the menu, waits
# until the program is running (its pid file exists and the process is alive), quits Eclipse through File > Exit, waits for Eclipse to end, and
# then polls until the program's process is gone. Exit 0 when it is gone, 1 when it is still running (the process is then stopped by this
# script, so no run leaves it behind), 2 when the tools to look at the screen are missing or the program never started (nothing is claimed).
#
# Needs: Xvfb, xdotool, ImageMagick (import), a JDK (javac). Starts one Eclipse and one Xvfb and ends both, also when it fails.
set -uo pipefail

eclipse="${1:?the Eclipse directory}"
work="${2:?an empty work directory}"
name="jawata-exit-fixture"

for tool in Xvfb xdotool import javac; do
    command -v "$tool" >/dev/null || { echo "exit-with-run-check: $tool is missing; nothing was checked"; exit 2; }
done
[ -x "$eclipse/eclipse" ] || { echo "exit-with-run-check: no Eclipse at $eclipse"; exit 2; }
if [ -n "$(ls -A "$work" 2>/dev/null)" ]; then echo "exit-with-run-check: $work is not empty"; exit 2; fi
mkdir -p "$work/prog/src" "$work/prog/classes" "$work/shots" "$work/notes"
work="$(cd "$work" && pwd)"

# the program: writes its own process id, then lives until it is stopped
cat > "$work/prog/src/JawataExitFixtureSpin.java" <<'JAVA'
public class JawataExitFixtureSpin {
    public static void main(String[] args) throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        while (true) {
            System.out.println("tick");
            Thread.sleep(200);
        }
    }
}
JAVA
javac -d "$work/prog/classes" "$work/prog/src/JawataExitFixtureSpin.java" || { echo "exit-with-run-check: the fixture did not compile"; exit 2; }
pidfile="$work/prog/pid.txt"

# the person's run configuration: a Java application over the compiled folder, saved where Eclipse keeps local configurations
launches="$work/ws/.metadata/.plugins/org.eclipse.debug.core/.launches"
mkdir -p "$launches" "$work/ws/.metadata/.plugins/org.eclipse.core.runtime/.settings"
cat > "$launches/$name.launch" <<XML
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<launchConfiguration type="org.eclipse.jdt.launching.localJavaApplication">
<listAttribute key="org.eclipse.jdt.launching.CLASSPATH">
<listEntry value="&lt;?xml version=&quot;1.0&quot; encoding=&quot;UTF-8&quot; standalone=&quot;no&quot;?&gt;&#10;&lt;runtimeClasspathEntry externalArchive=&quot;$work/prog/classes&quot; path=&quot;3&quot; type=&quot;2&quot;/&gt;&#10;"/>
</listAttribute>
<booleanAttribute key="org.eclipse.jdt.launching.DEFAULT_CLASSPATH" value="false"/>
<stringAttribute key="org.eclipse.jdt.launching.MAIN_TYPE" value="JawataExitFixtureSpin"/>
<stringAttribute key="org.eclipse.jdt.launching.PROGRAM_ARGUMENTS" value="$pidfile"/>
</launchConfiguration>
XML
printf 'eclipse.preferences.version=1\nshowIntro=false\n' > "$work/ws/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.ui.prefs"

display=""
for n in $(seq 90 120); do
    [ -e "/tmp/.X11-unix/X$n" ] || { display=":$n"; break; }
done
[ -n "$display" ] || { echo "exit-with-run-check: no free X display between :90 and :120"; exit 2; }
Xvfb "$display" -screen 0 1600x1000x24 > "$work/xvfb.out" 2>&1 &
xvfb=$!
eclipse_pid=""
program=""
cleanup() {
    [ -n "$eclipse_pid" ] && kill "$eclipse_pid" 2>/dev/null
    [ -n "$program" ] && kill "$program" 2>/dev/null
    kill "$xvfb" 2>/dev/null
}
trap cleanup EXIT
sleep 2
export DISPLAY="$display"

"$eclipse/eclipse" -nosplash ${EXIT_CHECK_ECLIPSE_ARGS:-} -data "$work/ws" -consoleLog -vmargs "-Djawata.engine.notes.dir=$work/notes" -Djawata.experience.store=memory > "$work/eclipse.out" 2>&1 &
eclipse_pid=$!
for _ in $(seq 1 60); do
    xdotool search --onlyvisible --name 'Eclipse' >/dev/null 2>&1 && break
    sleep 2
done
sleep 30

# the person chooses Run > Run for jawata > the configuration, with the keyboard: Alt+R opens the Run menu with its first entry, Run for
# jawata, already chosen; Right opens that submenu (read when it opens), whose first entry is the configuration
xdotool mousemove 500 400 click 1
sleep 1
xdotool key alt+r
sleep 2
import -window root "$work/shots/run-menu.png"
xdotool key Right
sleep 1
import -window root "$work/shots/run-submenu.png"
xdotool key Return

# the program is running when it has written its process id and that process is alive
for _ in $(seq 1 40); do
    [ -s "$pidfile" ] && break
    sleep 1
done
if [ ! -s "$pidfile" ]; then
    import -window root "$work/shots/no-program.png"
    echo "exit-with-run-check: the program never started, so nothing was checked (pictures in $work/shots, Eclipse's output in $work/eclipse.out)"
    exit 2
fi
program="$(cat "$pidfile")"
kill -0 "$program" 2>/dev/null || { echo "exit-with-run-check: the program wrote its pid ($program) but is not running; nothing was checked"; exit 2; }
echo "exit-with-run-check: the program runs (pid $program, started by Run for jawata); quitting Eclipse through File > Exit"

# the person quits Eclipse: File > Exit
import -window root "$work/shots/before-exit.png"
xdotool key alt+f
sleep 1
xdotool key x
for _ in $(seq 1 60); do kill -0 "$eclipse_pid" 2>/dev/null || break; sleep 1; done
if kill -0 "$eclipse_pid" 2>/dev/null; then
    import -window root "$work/shots/eclipse-did-not-exit.png"
    echo "exit-with-run-check: Eclipse did not close through File > Exit within 60 s; nothing was concluded about the program (picture $work/shots/eclipse-did-not-exit.png)"
    exit 2
fi
eclipse_pid=""

# the promise: the program is gone, within 15 s of Eclipse's end
for _ in $(seq 1 15); do
    kill -0 "$program" 2>/dev/null || break
    sleep 1
done
if kill -0 "$program" 2>/dev/null; then
    echo "exit-with-run-check: FAIL Eclipse has quit and the program started by Run for jawata is still running (pid $program); it is stopped now so no run leaves it behind"
    exit 1
fi
program=""
echo "exit-with-run-check: ok Eclipse was quit through File > Exit with a program started by Run for jawata running, and the program is gone"
exit 0
