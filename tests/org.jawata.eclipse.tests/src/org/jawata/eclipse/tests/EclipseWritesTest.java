package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.OperationHistoryFactory;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.ui.ide.undo.WorkspaceUndoUtil;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EclipseWrites;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.spi.host.McpEndpoint;
import org.jawata.spi.host.WorkspaceWrites;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 18, W3: how the engine's changes reach the person's files inside Eclipse, in the real workspace. A change the person
 * keeps is one step on Edit › Undo and one Undo restores the files; a trial is never on the undo history; a change over a file whose
 * editor holds unsaved text is refused by name and the text is left untouched; and a run of write tools leaves the project
 * configuration files as they were.
 */
public class EclipseWritesTest {

    private static final String PLUGIN = "org.jawata.eclipse";
    private static final IOperationHistory HISTORY = OperationHistoryFactory.getOperationHistory();
    private static final IUndoContext UNDO = WorkspaceUndoUtil.getWorkspaceUndoContext();

    /** The fixture rename, with the written reason the engine's precedent check asks for when a name was reverted before in this session. */
    private static final String RENAME = "{\"symbol\":\"p.Greeter#hello\",\"newName\":\"hi\","
        + "\"precedentOverride\":\"the test renames its own fixture symbol on purpose, and renames and undoes it more than once\"}";

    /** An MCP client at the running engine, once the engine can see a Greeter. */
    private static McpClient engineSeeing(IProject project) throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        McpClient client = McpClient.connect(at.url());
        Await.found("the engine sees " + project.getName(), () -> {
            try {
                return McpClient.text(client.call("search_symbols", "{\"query\":\"Greeter\"}")).contains("qualifiedName")
                    ? Optional.of(client) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        });
        return client;
    }

    private static String text(IFile file) throws Exception {
        return Files.readString(Path.of(file.getLocation().toOSString()));
    }

    private static int undoSteps() {
        return HISTORY.getUndoHistory(UNDO).length;
    }

    @Test
    public void aRenameAcceptedIsUndoneByOneUndo() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-writes-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            McpClient client = engineSeeing(project);
            int before = undoSteps();

            JsonNode answer = client.call("rename_symbol", RENAME);

            assertFalse(McpClient.failed(answer), "the rename was accepted: " + answer);
            assertTrue(Await.until(() -> {
                try {
                    return text(file).contains("String hi()");
                } catch (Exception e) {
                    return false;
                }
            }), "the file carries the new name: " + text(file) + " (the tool answered: " + McpClient.text(answer) + ")");
            assertEquals(before + 1, undoSteps(), "the rename is one step on Edit > Undo");

            HISTORY.undo(UNDO, new NullProgressMonitor(), null);

            file.refreshLocal(IFile.DEPTH_ZERO, null);
            assertTrue(text(file).contains("String hello()"), "one Undo restores the file: " + text(file));
            assertEquals(before, undoSteps(), "and the step is gone from the undo history");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void anEditOverUnsavedEditorTextIsRefusedAndTheTextIsUntouched() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-unsaved-");
        ITextFileBufferManager buffers = FileBuffers.getTextFileBufferManager();
        IFile file = project.getFile("src/p/Greeter.java");
        buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
        try {
            McpClient client = engineSeeing(project);
            ITextFileBuffer buffer = buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            IDocument document = buffer.getDocument();
            document.replace(0, 0, "// typed, not saved\n");
            assertTrue(buffer.isDirty(), "the editor's buffer holds unsaved text");
            int before = undoSteps();

            JsonNode answer = client.call("rename_symbol", RENAME);

            assertTrue(McpClient.text(answer).contains(EclipseWrites.UNSAVED_IN_EDITOR), "the refusal is named: " + McpClient.text(answer));
            assertTrue(document.get().startsWith("// typed, not saved\n"), "the person's unsaved text is still there");
            assertTrue(document.get().contains("String hello()"), "and nothing was renamed in the editor: " + document.get());
            assertTrue(text(file).contains("String hello()") && !text(file).contains("typed, not saved"), "and nothing was written to the file");
            assertEquals(before, undoSteps(), "and nothing reached the undo history");
        } finally {
            ITextFileBuffer buffer = buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            if (buffer != null) {
                buffer.revert(null);
            }
            buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void aTrialIsNeverOnTheUndoHistoryAndAnAcceptedChangeIs() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-trial-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();
            int at = text(file).indexOf("hello");

            TextFileChange trial = new TextFileChange("jawata trial", file);
            trial.setEdit(new ReplaceEdit(at, "hello".length(), "hey"));
            trial.initializeValidationData(new NullProgressMonitor());
            WorkspaceWrites.Applied applied = writes.perform(trial, WorkspaceWrites.WriteMode.TRIAL, new NullProgressMonitor());

            assertTrue(text(file).contains("String hey()"), "the trial was applied: " + text(file));
            assertEquals(before, undoSteps(), "and it is not on the undo history");
            applied.undo().initializeValidationData(new NullProgressMonitor());
            new PerformChangeOperation(applied.undo()).run(new NullProgressMonitor());
            assertTrue(text(file).contains("String hello()"), "the trial's own undo restores the file");

            TextFileChange accepted = new TextFileChange("jawata accepted", file);
            accepted.setEdit(new ReplaceEdit(at, "hello".length(), "hey"));
            accepted.initializeValidationData(new NullProgressMonitor());
            writes.perform(accepted, WorkspaceWrites.WriteMode.ACCEPTED, new NullProgressMonitor());

            assertEquals(before + 1, undoSteps(), "an accepted change is one step on the undo history");
            HISTORY.undo(UNDO, new NullProgressMonitor(), null);
            file.refreshLocal(IFile.DEPTH_ZERO, null);
            assertTrue(text(file).contains("String hello()"), "and one Undo restores it");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    /** A trial the way the engine's gate applies a change forward: nothing on the undo history yet, and the answer to hand to keep. */
    private static WorkspaceWrites.Applied trialRename(EclipseWrites writes, IFile file, String to) throws Exception {
        int at = text(file).indexOf("hello");
        TextFileChange trial = new TextFileChange("jawata gate trial", file);
        trial.setEdit(new ReplaceEdit(at, "hello".length(), to));
        trial.initializeValidationData(new NullProgressMonitor());
        return writes.perform(trial, WorkspaceWrites.WriteMode.TRIAL, new NullProgressMonitor());
    }

    @Test
    public void aTrialTheGateAcceptsIsKeptAsOneStepOnEditUndoAndOneUndoRestoresIt() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-keep-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();
            WorkspaceWrites.Applied applied = trialRename(writes, file, "hey");
            assertEquals(before, undoSteps(), "a trial is not on the undo history while the gate judges it");

            assertTrue(writes.keep(applied, "jawata gate trial"), "the host kept it");

            assertEquals(before + 1, undoSteps(), "the accepted change is one step on Edit > Undo");
            assertEquals("jawata gate trial", HISTORY.getUndoHistory(UNDO)[HISTORY.getUndoHistory(UNDO).length - 1].getLabel(),
                "under the label the engine gave it");
            HISTORY.undo(UNDO, new NullProgressMonitor(), null);
            file.refreshLocal(IFile.DEPTH_ZERO, null);
            assertTrue(text(file).contains("String hello()"), "one Undo restores the file: " + text(file));
            assertEquals(before, undoSteps(), "and the step is gone");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void keepingTheSameTrialTwiceLeavesOneStepNotTwo() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-keeptwice-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();
            WorkspaceWrites.Applied applied = trialRename(writes, file, "hey");

            assertTrue(writes.keep(applied, "jawata gate trial"));
            assertTrue(writes.keep(applied, "jawata gate trial"), "asking again is not a failure");

            assertEquals(before + 1, undoSteps(), "but the change is on the undo history once: a second step would undo a change already undone");
            HISTORY.undo(UNDO, new NullProgressMonitor(), null);
            assertEquals(before, undoSteps(), "so one Undo empties it");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void aTrialTheGateTakesBackIsNeverOnTheUndoHistory() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-takenback-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();
            WorkspaceWrites.Applied applied = trialRename(writes, file, "hey");

            // what the gate does with a change it refuses: it performs the change's undo, also as a trial, and never calls keep
            applied.undo().initializeValidationData(new NullProgressMonitor());
            writes.perform(applied.undo(), WorkspaceWrites.WriteMode.TRIAL, new NullProgressMonitor());

            assertTrue(text(file).contains("String hello()"), "the gate took it back: " + text(file));
            assertEquals(before, undoSteps(), "neither the change nor its rollback is a step the person made");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    /** An edit that is one step on Edit > Undo, performed as the person's own earlier step: a comment on the first line. */
    private static void earlierStep(EclipseWrites writes, IFile file) throws Exception {
        TextFileChange earlier = new TextFileChange("jawata earlier step", file);
        earlier.setEdit(new ReplaceEdit(0, 0, "// earlier step\n"));
        earlier.initializeValidationData(new NullProgressMonitor());
        writes.perform(earlier, WorkspaceWrites.WriteMode.ACCEPTED, new NullProgressMonitor());
    }

    /** What the engine does with a change it kept and then takes back: it performs the change's undo, which it got from the first perform. */
    private static void engineTakesBack(EclipseWrites writes, WorkspaceWrites.Applied applied) throws Exception {
        applied.undo().initializeValidationData(new NullProgressMonitor());
        writes.perform(applied.undo(), WorkspaceWrites.WriteMode.UNDO, new NullProgressMonitor());
    }

    private static void assertTakenBackLeavesOnlyTheEarlierStep(IFile file, int before, int redoBefore, String why) throws Exception {
        assertTrue(text(file).contains("String hello()"), why + ": the engine's undo restored the file: " + text(file));
        assertEquals(before + 1, undoSteps(), why + ": Edit > Undo lists neither the kept step nor the take-back, and the earlier step stays");
        assertEquals("jawata earlier step", HISTORY.getUndoHistory(UNDO)[HISTORY.getUndoHistory(UNDO).length - 1].getLabel(),
            why + ": the step on top of Edit > Undo is the person's earlier one, not the stale kept step");
        assertEquals(redoBefore, HISTORY.getRedoHistory(UNDO).length, why + ": and the take-back left nothing on Edit > Redo");
        HISTORY.undo(UNDO, new NullProgressMonitor(), null);
        file.refreshLocal(IFile.DEPTH_ZERO, null);
        assertTrue(text(file).contains("String hello()") && !text(file).contains("earlier step"),
            why + ": one Undo undoes the earlier step and nothing else: " + text(file));
        assertEquals(before, undoSteps(), why + ": and the history is as it was");
    }

    @Test
    public void aKeptChangeTheEngineTakesBackLeavesNoStepOnEditUndoAndTheEarlierStepStays() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-takeback-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();
            earlierStep(writes, file);
            int redoBefore = HISTORY.getRedoHistory(UNDO).length;   // after the earlier step: adding a step empties Redo, whatever an earlier test left there

            // kept as a trial the gate accepted: the way the engine keeps every change it applies
            WorkspaceWrites.Applied applied = trialRename(writes, file, "hey");
            assertTrue(writes.keep(applied, "jawata gate trial"), "the host kept it");
            assertEquals(before + 2, undoSteps(), "the kept change is a second step on Edit > Undo");

            engineTakesBack(writes, applied);

            assertTakenBackLeavesOnlyTheEarlierStep(file, before, redoBefore, "kept as a trial");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void aChangePerformedAsAcceptedAndTakenBackByTheEngineLeavesNoStepEither() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-takeback-acc-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();
            earlierStep(writes, file);
            int redoBefore = HISTORY.getRedoHistory(UNDO).length;   // after the earlier step: adding a step empties Redo, whatever an earlier test left there
            TextFileChange accepted = new TextFileChange("jawata accepted", file);
            accepted.setEdit(new ReplaceEdit(text(file).indexOf("hello"), "hello".length(), "hey"));
            accepted.initializeValidationData(new NullProgressMonitor());
            WorkspaceWrites.Applied applied = writes.perform(accepted, WorkspaceWrites.WriteMode.ACCEPTED, new NullProgressMonitor());
            assertEquals(before + 2, undoSteps(), "the accepted change is a second step on Edit > Undo");

            engineTakesBack(writes, applied);

            assertTakenBackLeavesOnlyTheEarlierStep(file, before, redoBefore, "performed as accepted");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void aTakeBackOfAChangeThatWasNeverKeptTouchesNoStep() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-takeback-none-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();
            earlierStep(writes, file);
            int redoBefore = HISTORY.getRedoHistory(UNDO).length;   // after the earlier step: adding a step empties Redo, whatever an earlier test left there
            WorkspaceWrites.Applied trial = trialRename(writes, file, "hey");

            engineTakesBack(writes, trial);

            assertTakenBackLeavesOnlyTheEarlierStep(file, before, redoBefore, "never kept");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void keepingWhatWasNotAppliedOrWasAlreadyAcceptedAddsNothing() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-keepnone-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            int before = undoSteps();

            assertFalse(writes.keep(new WorkspaceWrites.Applied(null, null), "nothing was done"), "a change with no undo cannot be kept");
            assertFalse(writes.keep(null, "no answer"), "and neither can no answer at all");
            assertEquals(before, undoSteps(), "and nothing reached the history");

            TextFileChange accepted = new TextFileChange("jawata accepted", file);
            accepted.setEdit(new ReplaceEdit(text(file).indexOf("hello"), "hello".length(), "hey"));
            accepted.initializeValidationData(new NullProgressMonitor());
            WorkspaceWrites.Applied applied = writes.perform(accepted, WorkspaceWrites.WriteMode.ACCEPTED, new NullProgressMonitor());
            assertEquals(before + 1, undoSteps(), "an accepted change is a step already");

            assertTrue(writes.keep(applied, "jawata accepted"), "keeping it is not a failure");
            assertEquals(before + 1, undoSteps(), "and does not make a second step for the same change");
            HISTORY.undo(UNDO, new NullProgressMonitor(), null);
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void aNonUtf8FileWithNonAsciiTextIsRefusedAndAnAsciiOneIsNot() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-charset-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            Path onDisk = Path.of(file.getLocation().toOSString());
            file.setCharset("ISO-8859-1", null);
            String ascii = text(file);
            Files.write(onDisk, ascii.replace("public class", "// café\npublic class").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            file.refreshLocal(IFile.DEPTH_ZERO, null);
            McpClient client = engineSeeing(project);
            int before = undoSteps();

            JsonNode answer = client.call("rename_symbol", RENAME);

            assertTrue(McpClient.text(answer).contains(EclipseWrites.CHARSET_NOT_UTF8), "the refusal is named: " + McpClient.text(answer));
            assertEquals(before, undoSteps(), "nothing reached the undo history");
            assertTrue(new String(Files.readAllBytes(onDisk), java.nio.charset.StandardCharsets.ISO_8859_1).contains("String hello()"),
                "and the file was not rewritten");

            Files.write(onDisk, ascii.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            file.refreshLocal(IFile.DEPTH_ZERO, null);
            assertTrue(new EclipseWrites(PLUGIN).refusal(List.of(file)).isEmpty(),
                "the same file in the same encoding with only ASCII in it is not refused: a workspace default that is not UTF-8 refuses nothing it need not");
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    @Test
    public void aTrialOverUnsavedTextIsRefusedAndNeverOnTheHistory() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-trialdirty-");
        ITextFileBufferManager buffers = FileBuffers.getTextFileBufferManager();
        IFile file = project.getFile("src/p/Greeter.java");
        buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
        try {
            ITextFileBuffer buffer = buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            buffer.getDocument().replace(0, 0, "// typed, not saved\n");
            int before = undoSteps();
            TextFileChange trial = new TextFileChange("jawata trial", file);
            trial.setEdit(new ReplaceEdit(text(file).indexOf("hello"), "hello".length(), "hey"));
            trial.initializeValidationData(new NullProgressMonitor());

            org.eclipse.core.runtime.CoreException refused = org.junit.jupiter.api.Assertions.assertThrows(org.eclipse.core.runtime.CoreException.class,
                () -> new EclipseWrites(PLUGIN).perform(trial, WorkspaceWrites.WriteMode.TRIAL, new NullProgressMonitor()));

            assertTrue(refused.getStatus().getMessage().contains(EclipseWrites.UNSAVED_IN_EDITOR), refused.getStatus().getMessage());
            assertEquals(before, undoSteps(), "a refused trial is not on the undo history");
            assertTrue(buffer.getDocument().get().startsWith("// typed, not saved\n"), "and the unsaved text is untouched");
        } finally {
            ITextFileBuffer buffer = buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            if (buffer != null) {
                buffer.revert(null);
            }
            buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    /** The project description, class path and settings of a project, with their content: what a write tool must not touch. */
    private static Map<String, String> configurationOf(IProject project) throws Exception {
        Map<String, String> files = new TreeMap<>();
        Path root = Path.of(project.getLocation().toOSString());
        List<Path> candidates = new ArrayList<>(List.of(root.resolve(".project"), root.resolve(".classpath")));
        if (Files.isDirectory(root.resolve(".settings"))) {
            try (Stream<Path> walk = Files.walk(root.resolve(".settings"))) {
                walk.filter(Files::isRegularFile).forEach(candidates::add);
            }
        }
        for (Path file : candidates) {
            if (Files.isRegularFile(file)) {
                files.put(root.relativize(file).toString(), Files.readString(file));
            }
        }
        return files;
    }

    @Test
    public void aRunOfWriteToolsLeavesTheConfigurationFilesAsTheyWere() throws Exception {
        IProject project = WorkspaceProjects.java("jawata-writetools-");
        try {
            IFile file = project.getFile("src/p/Greeter.java");
            McpClient client = engineSeeing(project);
            Map<String, String> before = configurationOf(project);
            String path = file.getLocation().toOSString();

            Map<String, JsonNode> answers = new java.util.LinkedHashMap<>();
            answers.put("rename_symbol", client.call("rename_symbol", RENAME));
            answers.put("organize_imports", client.call("organize_imports", "{\"filePath\":\"" + path + "\"}"));
            answers.put("apply_cleanup", client.call("apply_cleanup", "{\"kind\":\"add_final\",\"filePath\":\"" + path + "\"}"));
            answers.forEach((tool, answer) -> assertFalse(McpClient.failed(answer), tool + " answered with an error: " + answer));
            assertTrue(Await.until(() -> {
                try {
                    return text(file).contains("String hi()");
                } catch (Exception e) {
                    return false;
                }
            }), "and the run really wrote: the rename is in the file, so 'configuration unchanged' is not 'nothing happened': " + text(file));

            assertEquals(before, configurationOf(project), "the write tools changed a configuration file");

            // The control: the same snapshot does see a settings file appear, so "unchanged" above is not a snapshot that cannot see.
            Path settings = Path.of(project.getLocation().toOSString()).resolve(".settings");
            Files.createDirectories(settings);
            Files.writeString(settings.resolve("control.prefs"), "control=true\n");
            assertFalse(before.equals(configurationOf(project)), "the snapshot did not notice a new settings file");
            Files.delete(settings.resolve("control.prefs"));
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }
}
