# os-check on linux

- started: 2026-10-28T19:50:09Z (UTC)
- machine: Linux 7.0.9-76070009-generic, x86_64; the run used Xvfb, a virtual screen; the desktop the person sits at: x11
- plug-in commit: 567fa73 (working tree has uncommitted changes)
- exit code of scripts/28k-e2e.sh: 0; promises passed: 100; failed: 0

## What the run said

```
  ok    P0 READY line has the captured shape
  ok    P0 health_check toolCount is 44, the README's inventory
  ok    P0 tools/list equals the golden: 44 names in order, schemas identical
  ok    P1 compile_workspace: 0, then 1 error(s) in Clean.java after an edit on disk, then 0 after restoring it
  ok    P2 rename_symbol applied (greet -> welcome), compile_workspace 0 errors, refactoring undo restored Clean.java byte for byte
  ok    P3 experience(record) + recall(symbol): com.example.Clean#greet resolves to Clean.java line 19, the line the file on disk declares it on
  ok    P3 run_tests on com.example.SampleTest ran and reported its count: total 7, passed 4, failed 1, skipped 2
  ok    P4 tools/list rendered as the golden was is build/28k-golden-tools.json byte for byte: 44 tools, 139364 bytes
  ok    P4 refactoring plan -> apply_plan (2 steps) -> undo_plan: ComposeMethodTargets.java changed, then restored byte for byte
  ok    P5 -Djawata.backups.depth=3 passed at start takes effect: experience(kind=restore) answers depth 3, not the default 10
  ok    P5 a second start on the taken port exits 13 with the golden text
  ok    P5 a deaf listener under the built dist ended the process with status 70 after 85s, and said why
  ok    P6 with no language bound the engine says it is loading: status 'Loading project...'
  ok    P6 only the tools that name no language are published: 10 of 44, no search_symbols
  ok    P7 the resident jar holds the application class
  ok    P7 the resident jar holds the workspace manager and the importer
  ok    P7 the resident extension names the application under the unchanged id
  ok    P7 the engine jar holds no application class and no extension
  ok    P7 the core jar holds no workspace-building class
  ok    P7 the Gradle tooling jar travels with the resident, not with core
  ok    P7 the READY line of a -token-file start has the golden shape
  ok    P7 the token file holds the token and a line separator, mode 600
  ok    alpha resolves its OWN symbol — the miss below is a real miss
  ok    alpha does not resolve beta's symbol
  ok    the miss ANSWERS — it names the workspace AND the project
  ok    the WORKSPACE slot holds the workspace name
  ok    the PROJECT slot holds the project key
  ok    the naming-only tail is replaced by the answer
  ok    nothing in the request asked for this — arguments were kind+symbol only
  ok    with beta stopped the search says it consulted ZERO siblings
  ok    alpha no longer claims beta holds it
  ok    P8-S4 the four members load, the Maven module lib nested in the root module as a project of its own: ['core', 'intellij-root-plain', 'lib', 'tools']
  ok    P8-S4 the root module mounts exactly the folders it declares, src and the merged core/java: ['src-0-src', 'src-1-core-java']
  ok    P8-S4 core mounts its main and its test folder: ['src-0-java', 'src-1-test']
  ok    P8-S4 tools mounts its src: ['src-0-src']
  ok    P8-S4 lib, which declares nothing, is discovered as before (Maven layout): ['src-0-src-main-java']
  ok    P8 health_check lists the stand-in language beside java: ['fake', 'java']
  ok    P8 the stand-in's tool is published beside the 44: 45 tools, fake_echo present: True
  ok    P8 the stand-in's tool answers: {"success": true, "data": {"echo": "hello", "language": "fake"}, "meta": {"steering": "Grounded next step: verify with compile_workspace + get_diagnostics; JAWA
  ok    P8 a symbol this resident lacks is answered from the live Eclipse note: ...(s): compile-clean) — a symbol that lives in another project tree is served by that tree's own jawata server, not this one. Not here — workspace 'live' has it, in project 'eclipse-project'. (consulted 1 of 1 sibling workspace(s).)
  ok    P8 exactly one engine was consulted of one listed: the stale, malformed and newer-major notes were not listed
  ok    P8 the live engine was asked once, as a peek, without a key: [{'authorization': None, 'peek': '1'}]
  ok    P8 the server behind the stale note was never asked: []
  ok    the engine's promises hold (its own lines above)
  ok    R8: the update site ships 10 bundles, none of JDT, LTK, SWT, JFace, an slf4j provider, jdtpatch or resident
  ok    P15/R9: the update site ships the 8 engine bundles and libraries the engine feature names, each byte-identical to the file of the same name in the released engine 4.4.17
  ok    R9: no jawata-mcp build file names org.jawata.eclipse
  ok    the commit hook is installed in this clone and its test passes
  ok    P13 java: the update site installs (director), org.jawata.eclipse.feature.feature.group is an installed root
  ok    P13 java: Eclipse starts in Xvfb, the workbench is ACTIVE; both org.jawata bundles, WikiText's Markdown editor and the Terminal's local shell are resolved
  ok    P15 java: in that started Eclipse the eight engine bundles and libraries (spi, core, mcp, catalogue, H2, Jackson x3) are resolved on its own JDT, LTK and JaCoCo
  ok    D6/D7 java: the PDE tests ran inside this download (Tests run: 148, Failures: 0, Errors: 0, Skipped: 2): a .md opens in jawata's editor with source and a preview showing every table, the Terminal runs bash
  ok    D2 java: the read-only run's Maven, Gradle and plug-in fixtures had their real natures in this download (natures present here: m2e=true, buildship=true, pde=false)
  ok    P17 java: the preference page shows every setting of the engine, and a value saved on it changes a tool answer for a client with no key, on a page that scrolls (Tests run: 4; the two proving tests are in the report and passed)
  ok    P18 java: a rename is one Undo step and one Undo restores it, a trial is not on the undo history, unsaved editor text is refused by name and untouched, write tools leave the configuration alone (Tests run: 6)
  ok    P19 java: Run for jawata starts the program in Run mode with the debug agent on an unsaved copy and the saved file is untouched, the debugger attaches, the person's Debug ends the agent's session and is not shared (Tests run: 10)
  ok    P21 java: the chat bundle and its 14 test classes are in the build and passed in this download: the agent relay, the engine's tools offered to the agent, the page and its contract, the host-port conformance, the replayed sessions, the preference page and dialogs, the Eclipse commands, the theme map and a theme change, and a long tool call that leaves the interface and the focus alone
  ok    P22 java: the import command on the fixture workspace gives the projects an import by hand gives, through Eclipse's own Smart Import wizard, the person confirming each folder (Tests run: 6); path taken: the registry wizard, pre-filled by name
  ok    P13b java: the fixture's comment, suggested change, typed addition and typed removal show as five callouts in the preview; accepting, rejecting and resolving each change only their own mark through the real commands (Tests run: 1; unit: Tests run: 14)
  ok    P23 java: the chat view opens in the middle or in a side panel as chosen, loads and takes a command in each, the real first-run question has two buttons that each choose their place and asks once, a chat closed from the middle opens in a side panel when that is now chosen, a chat that carries the saved mark is not moved, an open chat stays, and the saved layout holds the view with its mark (Tests run: 10)
  ok    P23 java: the chat asks its page to reopen the last conversation when the person wants that, keeps the conversation a prompt, load or resume belongs to, and the preference page saves both choices (Tests run: 5)
  ok    D6 java: Eclipse's log in the test run has no rejected registration and no exception from jawata's code
  ok    P16 java: the engine started with the workbench and wrote its note (eclipse-121af3c0ba6c9eba.json)
  ok    P16 java: the note says hostKind eclipse, names this workspace, and carries no key
  ok    P16 java: a client at the note's endpoint with no key lists 44 tools, the same names as the headless golden of engine 4.4.17 (R7)
  ok    P16 java: health_check answers toolCount 44
  ok    P16 java: load_project and project add both answer HOST_OWNS_WORKSPACE
  ok    P16 java: a clean stop deleted the note
  ok    P16 java: relaunched on a second workspace the note names that workspace and its engine answers (toolCount 44)
  ok    P20 java: one Eclipse open: studio's reader shows 1 running Eclipse workspace, no switch, no note deleted
  ok    P20 java: after that Eclipse closed, studio still lists its workspace, not running (it remembered it)
  ok    P20 java: two Eclipse instances open and one closed: studio answers 2 running, 1 not running, no switch on any, the tray rows are the list's rows (3 rows)
  ok    P24 java: the chat was opened in the middle, the layout Eclipse wrote on exit holds the view and its mark, and after a real restart the chat was on screen without anyone opening it (picture 4 of session 2, 8 s after the start)
  ok    P24 java: the first time the chat was opened the window "Where should the jawata chat open?" was on screen, its first button put the chat in the middle and was kept as placement=editor, and a second opening with the choice stored did not ask again
  ok    P13 rcp: the update site installs (director), org.jawata.eclipse.feature.feature.group is an installed root
  ok    P13 rcp: Eclipse starts in Xvfb, the workbench is ACTIVE; both org.jawata bundles, WikiText's Markdown editor and the Terminal's local shell are resolved
  ok    P15 rcp: in that started Eclipse the eight engine bundles and libraries (spi, core, mcp, catalogue, H2, Jackson x3) are resolved on its own JDT, LTK and JaCoCo
  ok    D6/D7 rcp: the PDE tests ran inside this download (Tests run: 148, Failures: 0, Errors: 0, Skipped: 2): a .md opens in jawata's editor with source and a preview showing every table, the Terminal runs bash
  ok    D2 rcp: the read-only run's Maven, Gradle and plug-in fixtures had their real natures in this download (natures present here: m2e=true, buildship=true, pde=true)
  ok    P17 rcp: the preference page shows every setting of the engine, and a value saved on it changes a tool answer for a client with no key, on a page that scrolls (Tests run: 4; the two proving tests are in the report and passed)
  ok    P18 rcp: a rename is one Undo step and one Undo restores it, a trial is not on the undo history, unsaved editor text is refused by name and untouched, write tools leave the configuration alone (Tests run: 6)
  ok    P19 rcp: Run for jawata starts the program in Run mode with the debug agent on an unsaved copy and the saved file is untouched, the debugger attaches, the person's Debug ends the agent's session and is not shared (Tests run: 10)
  ok    P21 rcp: the chat bundle and its 14 test classes are in the build and passed in this download: the agent relay, the engine's tools offered to the agent, the page and its contract, the host-port conformance, the replayed sessions, the preference page and dialogs, the Eclipse commands, the theme map and a theme change, and a long tool call that leaves the interface and the focus alone
  ok    P22 rcp: the import command on the fixture workspace gives the projects an import by hand gives, through Eclipse's own Smart Import wizard, the person confirming each folder (Tests run: 6); path taken: the registry wizard, pre-filled by name
  ok    P13b rcp: the fixture's comment, suggested change, typed addition and typed removal show as five callouts in the preview; accepting, rejecting and resolving each change only their own mark through the real commands (Tests run: 1; unit: Tests run: 14)
  ok    P23 rcp: the chat view opens in the middle or in a side panel as chosen, loads and takes a command in each, the real first-run question has two buttons that each choose their place and asks once, a chat closed from the middle opens in a side panel when that is now chosen, a chat that carries the saved mark is not moved, an open chat stays, and the saved layout holds the view with its mark (Tests run: 10)
  ok    P23 rcp: the chat asks its page to reopen the last conversation when the person wants that, keeps the conversation a prompt, load or resume belongs to, and the preference page saves both choices (Tests run: 5)
  ok    D6 rcp: Eclipse's log in the test run has no rejected registration and no exception from jawata's code
  ok    P16 rcp: the engine started with the workbench and wrote its note (eclipse-59dbae65b66caba7.json)
  ok    P16 rcp: the note says hostKind eclipse, names this workspace, and carries no key
  ok    P16 rcp: a client at the note's endpoint with no key lists 44 tools, the same names as the headless golden of engine 4.4.17 (R7)
  ok    P16 rcp: health_check answers toolCount 44
  ok    P16 rcp: load_project and project add both answer HOST_OWNS_WORKSPACE
  ok    P16 rcp: a clean stop deleted the note
  ok    P16 rcp: relaunched on a second workspace the note names that workspace and its engine answers (toolCount 44)
  ok    P20 rcp: one Eclipse open: studio's reader shows 1 running Eclipse workspace, no switch, no note deleted
  ok    P20 rcp: after that Eclipse closed, studio still lists its workspace, not running (it remembered it)
  ok    P20 rcp: two Eclipse instances open and one closed: studio answers 2 running, 1 not running, no switch on any, the tray rows are the list's rows (3 rows)
  ok    P24 rcp: the chat was opened in the middle, the layout Eclipse wrote on exit holds the view and its mark, and after a real restart the chat was on screen without anyone opening it (picture 5 of session 2, 10 s after the start)
  ok    P24 rcp: the first time the chat was opened the window "Where should the jawata chat open?" was on screen, its first button put the chat in the middle and was kept as placement=editor, and a second opening with the choice stored did not ask again
```

## Not covered by this run

- After a restart the last conversation reopening (the chat being where it was is P24 above; reopening needs a real agent, plan 21.15).
- The callouts for D14's fixture and the commit draft with HEAD unchanged (waiting for the signed Amendment 1; stages 13b and 22b).
- R1 and a chat turn that calls a jawata tool in the real chat (plan 21.15).
- Windows and macOS: run by Harald.
