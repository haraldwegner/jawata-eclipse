# Sprint 28k, Stage 25: the as-built run over jawata-eclipse

Taken 2026-10-30 on jawata-eclipse `main` (the commit that holds `docs/as-built-28k.md`), through jawata's `find_quality_issue` (`called_only_by_tests`, `unused`) and `find_references`. It asks one question of every capability: which production code calls it. Production code here means the three bundles (`org.jawata.eclipse`, `.chat`, `.terminal`) and what their `plugin.xml` files declare; Eclipse calls a view, an editor, a command handler or a page through `plugin.xml`, a call the compiler's reference search cannot see, so each is read there.

## The count

- 136 public members of the three bundles are tracked. **19 are called only by tests; 0 are unused.**
- Of the 19, 18 are test seams or view identifiers, and **1 group is a capability with no production caller: path A of "Run for jawata"** (below).

## Each capability and what calls it in production

| Spec | Capability | Production entry, as declared | Result |
|---|---|---|---|
| D2 | The engine runs inside Eclipse | `EngineStartup`, `bundles/org.jawata.eclipse/plugin.xml:21` (early startup) | called |
| D3 | The chat | `ChatView`, `bundles/org.jawata.eclipse.chat/plugin.xml:9`; twelve commands, each handled by `ChatCommandHandler` (from line 48) | called |
| D4 | Run for jawata, path B (the person) | `RunForJawataMenu`, `bundles/org.jawata.eclipse/plugin.xml:33`; it calls `HostRuns#configurations` and `HostRuns#run` | called |
| D4 | Run for jawata, **path A (the agent asks)** | none in this repository | **no production caller** |
| D5 | Settings in preferences | `JawataPreferencePage`, `plugin.xml:26` | called |
| D6 | Markdown specs | `SpecMarkdownEditor`, `plugin.xml:11` | called |
| D7 | The terminal | `TerminalView`, `bundles/org.jawata.eclipse.terminal/plugin.xml:10` | called |
| D8 | VS Code import | `ImportCodeWorkspaceHandler`, `plugin.xml:47` | called |
| D10 | Where the chat sits | `ChatPlacementChoice`, called from `ChatPreferencePage:105` and `:141` | called |
| D13 | Commit drafts | `CommitDraftView`, `plugin.xml:117`; its "Ask the agent" button runs the chat command `org.jawata.eclipse.chat.sendPrompt` by id | called |
| D14 | Comments and changes in the Markdown reader | `CalloutCommandHandler` for the five commands, `plugin.xml:78` to `90`; it applies each `CriticMarkup.Edit` to the open document | called |
| D9 | One workspace list in studio | in jawata-studio, `src-tauri/src/eclipse_notes.rs`, called from the list and the tray (released as studio v4.4.19) | called |
| D1, D11, D12 | The engine split, installs, the 28i rows | outside this repository's Java: engine release, update site, `jawata-chat` | not read here |

## The 19 members called only by tests

| Kind | Members | Reading |
|---|---|---|
| View and editor identifiers (3) | `CommitDraftView#ID`, `SpecMarkdownEditor#ID`, `TerminalView#ID` | `plugin.xml` declares the same strings; a test opens the view by id. Not a capability. |
| What a test reads off a view (7) | `CommitDraftView#shown`, `#copyEnabled`, `#askEnabled`; `JawataPreferencePage#shownKeys`, `#fieldFor`; `ChatView#page`; `TerminalView#browser` | observation seams. The person sees the same things on the screen. |
| Seams that let a test stand in for Eclipse (3) | `ChatPlacementChoice#askWith`, `TerminalView#sessionsWith`, `ChatCommandHandler#commandId` | the production path calls the real dialog, the real session and the real id. |
| What a test reads off the engine host (2) | `EclipseEngine#engine`, `EngineNoteWriter#file` | studio reads the notes from the folder, not through this accessor. |
| A computed result (1) | `CriticMarkup.Edit#applyTo` | the editor applies the same `Edit` to its open document (`CalloutCommandHandler:48`); `applyTo` gives a test the resulting text. The production apply is a second path to the same change; `CalloutsTest` runs it in the real editor. |
| **Path A (3)** | `HostRuns#AGENT`, `HostRuns#running`, `HostRuns#launchOf` | **see below** |

## What the run found

**Path A of "Run for jawata" has no production caller in jawata-eclipse.** `HostRuns#run` has two callers: `RunForJawataMenu:51` (path B, the person) and `HostRunsTest:131`. The constant `AGENT`, the list of live programs `running()` and `launchOf` serve the agent asking, and nothing calls them outside tests. The plan's status board already says this for Stage 19: "engine side owed to 28h". So D4's path A is built and tested here, and the engine tool that would ask for it does not exist yet. It is reported as **unproven at the front door**, home 28h, not as done. P19's path-A step in the end-to-end gate calls `HostRuns#run` directly, which proves the run, not the agent's request.

Nothing else is a capability held alive only by its tests.

## What this run did not read

- The engine (D1), the update site and installs (D11) and the `jawata-chat` rows (D12): another repository's code or a build artifact. Stage 11's audit covers the engine.
- Windows and macOS: Stage 24, with 28h.
