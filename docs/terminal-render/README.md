# Eclipse's own Terminal, measured (plan Stage 13c, 2026-10-28)

Eclipse 4.41 Java download with the plug-in installed, in a virtual screen (X11), a local bash opened from Quick Access, the commands typed with a key-press tool.

| What | Result |
|---|---|
| Typing into the terminal | works: the typed command ran and its output came back |
| 256 colours, true colour, bold, italic, underline, reverse | drawn correctly |
| Box drawing lines (the frame characters Claude Code draws its screen with) | the horizontal bars are missing and the frame is broken |
| Block characters | partly drawn |
| Emoji | shown as `??` |
| Changing the terminal font to DejaVu Sans Mono | no change to any of the above |

Pictures: `eclipse-terminal-default-font.png` and `eclipse-terminal-dejavu-sans-mono.png`.
The same output in a terminal on this machine was not captured at the same size; the plan asks for it beside these.

## The jawata terminal, same output (2026-10-28)

`jawata-terminal.png`: the jawata terminal view in the same Eclipse 4.41 Java download and virtual screen, the same fixture typed
(`bash /tmp/jawata-fixture.sh`: colour bars, bold/italic/underline/reverse, true colour, a box, block characters, check marks, an emoji).

| What | Eclipse's Terminal | jawata terminal |
|---|---|---|
| Typing | works | works |
| Colours and text styles | drawn | drawn |
| Box frame | horizontal bars missing, frame broken | one closed frame |
| Block characters | partly drawn | drawn |
| Emoji | `??` | drawn |

The same output in a native terminal on this machine was not captured: gnome-terminal gave a blank screen in the virtual display
(no desktop session there). To be compared by eye on his own screen.
