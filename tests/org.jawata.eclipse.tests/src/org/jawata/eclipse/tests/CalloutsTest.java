package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jawata.eclipse.specs.CalloutCommandHandler;
import org.jawata.eclipse.specs.SpecMarkdownEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Spec D14, plan Stage 13b: in the editor of a Markdown file a comment and a suggested change made with the two commands, and an addition
 * and a removal typed by hand, show as five callouts in the preview; accepting, rejecting and resolving go through the real commands, each
 * changing only the bytes of its own mark. The callouts are counted in the HTML the Preview tab's browser shows. The mark inside a code block
 * is not one of the five.
 */
@SuppressWarnings("restriction")
public class CalloutsTest {

    @AfterEach
    public void closeTheEditor() {
        Display.getDefault().syncExec(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false));
    }

    @Test
    public void fiveCalloutsShowAndEachCommandEditsOnlyItsOwnMark() throws Exception {
        String original = fixture();
        IFile file = WikiTextShowsSpecsTest.copyIntoWorkspace("callouts.md", original);
        assertEquals(SpecMarkdownEditor.ID, WikiTextShowsSpecsTest.open(file));
        WikiTextShowsSpecsTest.waitForTabs();

        select("comment made with the command", 0, "comment made with the command".length());
        run(CalloutCommandHandler.COMMENT, "which command?");
        String text = original.replace("comment made with the command", "comment made with the command{>>which command?<<}");
        assertEquals(text, documentText(), "Comment inserts its mark after the selection and changes nothing else");

        select("old name", 0, "old name".length());
        run(CalloutCommandHandler.SUGGEST_CHANGE, "new name");
        text = text.replace("old name", "{--old name--}{++new name++}");
        assertEquals(text, documentText(), "Suggest change marks the selection removed and opens an addition after it");

        String html = WikiTextShowsSpecsTest.previewHtmlWhen(shown -> callouts(shown) >= 5);
        assertEquals(5, callouts(html), "one comment, one suggested change (a removal and an addition), one typed addition and one typed removal: " + html);
        assertTrue(html.contains("a mark inside code stays text"), "the mark inside the code block is shown as text, not as a callout: " + html);

        select("by hand++}", 0, 0);
        run(CalloutCommandHandler.ACCEPT, null);
        text = text.replace("{++by hand++}", "by hand");
        assertEquals(text, documentText(), "accepting an addition puts its text in the file and the mark is gone");
        assertEquals(4, callouts(WikiTextShowsSpecsTest.previewHtmlWhen(shown -> callouts(shown) <= 4)), "and its callout is gone from the preview");

        select("new name++}", 0, 0);
        run(CalloutCommandHandler.REJECT, null);
        text = text.replace("{++new name++}", "");
        assertEquals(text, documentText(), "rejecting an addition takes the mark and its text away");
        select("old name--}", 0, 0);
        run(CalloutCommandHandler.REJECT, null);
        text = text.replace("{--old name--}", "old name");
        assertEquals(text, documentText(), "rejecting a removal leaves its text as it was");

        select("which command?", 0, 0);
        run(CalloutCommandHandler.RESOLVE, null);
        text = text.replace("{>>which command?<<}", "");
        assertEquals(text, documentText(), "resolving the comment removes it");

        html = WikiTextShowsSpecsTest.previewHtmlWhen(shown -> callouts(shown) <= 1);
        assertEquals(1, callouts(html), "only the typed removal is left: " + html);
        assertEquals(original.replace("{++by hand++}", "by hand"), documentText(), "every other byte of the file is as it was");

        // the file itself: saved from the editor and read back from disk, it is plain text and an agent reading it finds the marks that are left
        Display.getDefault().syncExec(() -> editor().doSave(new org.eclipse.core.runtime.NullProgressMonitor()));
        String onDisk = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(original.replace("{++by hand++}", "by hand"), onDisk, "the saved file holds the text with its marks as written");
        org.junit.jupiter.api.Assertions.assertFalse(onDisk.contains("<span") || onDisk.contains("<ins") || onDisk.contains("<del"),
            "no HTML was written into the file: " + onDisk);
        assertEquals(java.util.List.of("REMOVAL"), org.jawata.eclipse.specs.CriticMarkup.marks(onDisk).stream().map(mark -> mark.kind().name()).toList(),
            "an agent reading the saved file finds the one mark that is left");
    }

    private static String fixture() throws Exception {
        try (InputStream in = FrameworkUtil.getBundle(CalloutsTest.class).getEntry("resources/callouts.md").openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** What a person sees as callouts: the marks WikiText carried into the preview. */
    private static int callouts(String html) {
        int n = 0;
        for (int at = html.indexOf("class=\"jawata-"); at >= 0; at = html.indexOf("class=\"jawata-", at + 1)) {
            n++;
        }
        return n;
    }

    private static ITextEditor editor() {
        AtomicReference<ITextEditor> found = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            IEditorPart part = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
            found.set((ITextEditor) part);
        });
        return found.get();
    }

    private static String documentText() {
        AtomicReference<String> text = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            ITextEditor editor = editor();
            text.set(editor.getDocumentProvider().getDocument(editor.getEditorInput()).get());
        });
        return text.get();
    }

    /** Puts the cursor in the document: {@code length} characters from {@code offset} characters after the first place {@code anchor} is found. */
    private static void select(String anchor, int offset, int length) {
        Display.getDefault().syncExec(() -> {
            ITextEditor editor = editor();
            IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            int at = document.get().indexOf(anchor);
            assertTrue(at >= 0, "the document holds " + anchor);
            editor.getSelectionProvider().setSelection(new TextSelection(document, at + offset, length));
        });
    }

    /** Runs one of the five commands as the editor's menu does, in the active editor. */
    private static void run(String commandId, String text) {
        Display.getDefault().syncExec(() -> {
            try {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                page.activate(page.getActiveEditor());
                ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
                Command command = commands.getCommand(commandId);
                Map<String, String> parameters = text == null ? Map.of() : Map.of(CalloutCommandHandler.TEXT_PARAMETER, text);
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class)
                    .executeCommand(ParameterizedCommand.generateCommand(command, parameters), null);
            } catch (Exception failed) {
                throw new AssertionError("the command " + commandId + " failed: " + failed, failed);
            }
        });
    }
}
