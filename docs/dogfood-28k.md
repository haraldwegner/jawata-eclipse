# Sprint 28k, Stage 26: jawata-mcp developed inside Eclipse with the plug-in

Taken 2026-10-30, on the plug-in at the commit that holds this file, by `scripts/28k-dogfood.sh` (test `DogfoodTest`, opt-in, never part of
`mvn verify`). The report of the last run is `.work/dogfood/dogfood-report.tsv` (61 calls, one line each, with the engine's answer).

## What was worked

- **The target.** A scratch clone of jawata-mcp at the tag `engine.version` names (v4.4.20), so a refactoring writes there and never into the
  person's checkout. Eight of its bundles are Java projects of the Eclipse workspace, located in the clone: `org.jawata.spi`, `.core`,
  `.catalogue`, `.mcp`, `.resident`, `.standin`, `.core.tests`, `.mcp.tests`. Eclipse's own Java builder compiled them: **0 errors in all 8**
  (`compile_workspace`, three times in the run).
- **The Eclipse.** A clean Eclipse 4.41 Java download in a virtual screen with the built update site installed into it; the engine started with the
  workbench and the test talked MCP to the address in its note, the way an agent does.
- **The questions.** Every one of the 44 published tools was asked something about jawata-mcp's own `LoadProjectTool` or the code around it
  (the test fails when one of the 44 is never asked and when an answer shows an engine fault: an internal error, an unknown tool, no text).

## Each claim and what came back

| Claim | Evidence | Result |
|---|---|---|
| All 44 tools are asked and none shows an engine fault | the report holds 44 distinct tools; no `INTERNAL_ERROR`, no `UNKNOWN_TOOL` | **shown** |
| 37 tools answer their question with a result | `health_check`, `search_symbols`, `find_references`, `inspect`, `go_to_definition`, `analyze`, `get_at_position`, `get_diagnostics`, `validate_syntax`, `get_call_hierarchy`, `find_field_writes`, `find_tests`, `find_pattern_usages`, `find_quality_issue`, `find_string_literals`, `find_duplicate_code` (110 groups over 11,930 methods), `find_modernization`, `project`, `field`, `rename_symbol`, `organize_imports`, `extract`, `inline`, `generate`, `change_method_signature`, `data`, `apply_cleanup`, `compile_workspace`, `refresh_workspace`, `dependency`, `format`, `quick_fix`, `experience`, `refactoring` (the undo), `run_tests`, `debug`, `profile` (the last three below); the 37 and the six refusals below and `load_project` make the 44 | **shown** |
| A refactoring applies and undoes | `rename_symbol` of the private field `workspace` to `hostWorkspace` in `LoadProjectTool`, applied through Eclipse's workspace: the file on disk changed and git saw it; compile errors 0 before and 0 after; `refactoring undo` put the file back **byte for byte** and git saw no change | **shown** |
| A test of jawata-mcp is run | `run_tests` on `org.jawata.mcp.TheFortyFourPublishedToolsTest` (4 of 4 passed), `org.jawata.mcp.models.ErrorInfoTest` (12 of 12), `org.jawata.core.ProjectKeysTest` (6 of 6) | **shown after finding F1 and its fix** |
| A debug session | launched JUnit's console launcher on `ErrorInfoTest` under the debugger, a method breakpoint on `ErrorInfo#getCode` bound when the class loaded and **hit at ErrorInfo.java line 75** (called from `ErrorInfoTest.refactoringFailed_includesReason`), `evaluate this.code` returned `REFACTORING_FAILED` | **shown** |
| A profile of the stopped JVM | `profile threads` listed the JVM's threads with the main thread "at breakpoint" and its stack; `profile gc` gave the heap and Metaspace figures | **shown** (`sample`/`hotspots`, the JFR recording, were not asked) |
| Memory's anchors resolve | a fact recorded against `LoadProjectTool` and promoted came back from `recall` by that class | **shown, on an in-memory store**: the test build sets `jawata.experience.store=memory` so the person's own store is untouched |
| The host owns the workspace | `load_project` answered `HOST_OWNS_WORKSPACE`; `project list` shows the 8 projects Eclipse holds | **shown** |
| Six tools declined the question I put | `delete_anonymous_implementation` (no anonymous class at that place), `move` (several possible targets: model, workspace), `hierarchy` (the superclass is Object, nothing to pull up to), `refactor_to_pattern decompose_conditional` (applies atomically, staging is not supported), `apply_null_annotations` (the project cannot resolve `org.jspecify.annotations.NonNull`), `hire` (the seat file I named does not exist) | **reached and judged, not shown to succeed**: each refusal is the engine doing its job on my question, but no positive case of these six ran inside Eclipse |

## Findings

**F1 (fixed, local commit, not released): `run_tests` was refused inside Eclipse.** All three test runs answered *"Neither jawata.tools.dir nor
jawata.dist.root is set — the distribution's tools/ folder cannot be located."* The headless engine finds the runner jars (the test runner,
JUnit's console launcher, JaCoCo's agent, PIT) beside its own bundles; inside Eclipse nothing did, so tests, coverage and mutation could not run
there, against the spec's D4 sentence *"Tests run through jawata's runner for gates"*. The fix: `scripts/fetch-engine.sh` also copies the release's
`tools/` folder into the plug-in bundle (`bundles/org.jawata.eclipse/tools/`, ignored by git like the chat page), the bundle includes it, and
`EnginePreferenceInitializer` puts the jars into the plug-in's state area (`BundledTools`) and answers `jawata.tools.dir` with that folder.
Evidence: the same three calls refused before and passed after the rebuilt site was installed; `EngineToolsTest` (two tests: the setting names a
folder holding the runner and JUnit's launcher byte for byte; a damaged jar is put back) ran in the full build, 184 tests, 0 failures, 2 skipped
(182 before). The cost is 6.6 MB in the update site. The engine is unchanged.

**F2 (a harness lesson, no change to the product): a rename inside a project Eclipse has not built is refused with "Found potential matches".**
In the first runs the projects had no Java builder (the nature was set before the project was opened, so Eclipse did not configure it) and
`rename_symbol` of `LoadProjectTool#log` was refused with an unhelpful message; once the projects were built it answered. The message does not
say that the project was never compiled.

## Not covered by this run

- `profile sample`, `hotspots` and the JFR recording; coverage and mutation through `run_tests` (the runner jars they need are now present, the
  calls were not made).
- A positive case of the six tools in the last row of the table.
- The chat and the note in studio: shown by other gates (the live agent turns of 2026-10-29, promise P20), not by this run.
- Eclipse's own **Edit › Undo** of an accepted refactoring: shown by `EclipseWritesTest`, not here (this run undid through the engine's own
  `refactoring undo`).
- Windows and macOS, and the hand-over smoke: with 28h, as in `docs/as-built-28k.md`.
