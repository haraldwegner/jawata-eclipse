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
