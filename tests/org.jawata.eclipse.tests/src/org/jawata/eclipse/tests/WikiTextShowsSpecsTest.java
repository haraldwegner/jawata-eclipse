package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.mylyn.internal.wikitext.ui.editor.MarkupEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.jawata.eclipse.specs.SpecMarkdownEditor;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Spec D6: a sprint document opens in Eclipse with its source and a preview, and the preview shows the specs' tables (his option
 * B: jawata's editor, WikiText's own reading the file with pipe tables). The file is a copy of the bundled spec-shaped fixture, and
 * of a real spec when the build is given one with -Djawata.spec.md=PATH (the specs stay out of this public repository). The tables
 * are counted in the HTML the Preview tab's browser shows — what the person sees, not a reconstruction of it.
 */
@SuppressWarnings("restriction")
public class WikiTextShowsSpecsTest {

    @Test
    public void theFixtureOpensWithSourceAndPreview() throws Exception {
        opensWithSourceAndPreview("spec-tables.md", fixture());
    }

    @Test
    public void aRealSpecOpensWithSourceAndPreview() throws Exception {
        Path spec = realSpec();
        opensWithSourceAndPreview(spec.getFileName().toString(), Files.readString(spec, StandardCharsets.UTF_8));
    }

    @Test
    public void theFixturesTablesRenderInThePreview() throws Exception {
        tablesRender("spec-tables.md", fixture());
    }

    @Test
    public void aRealSpecsTablesRenderInThePreview() throws Exception {
        Path spec = realSpec();
        tablesRender(spec.getFileName().toString(), Files.readString(spec, StandardCharsets.UTF_8));
    }

    private static String fixture() throws Exception {
        return new String(resource("resources/spec-tables.md"), StandardCharsets.UTF_8);
    }

    /** The real spec the build was given (-Djawata.spec.md=PATH, profile real-spec); without one the test is skipped, not passed. */
    private static Path realSpec() {
        String path = System.getProperty("jawata.spec.md", "");
        assumeFalse(path.isBlank(), "D6: no real spec given (-Djawata.spec.md); the fixture test stands for it");
        return Path.of(path);
    }

    private void opensWithSourceAndPreview(String name, String text) throws Exception {
        IFile file = copyIntoWorkspace(name, text);
        String editorId = open(file);
        List<String> tabs = waitForTabs();
        assertEquals(SpecMarkdownEditor.ID, editorId, name + " opens in jawata's Markdown editor by default");
        assertTrue(tabs.stream().anyMatch(t -> t.contains("Source")) && tabs.stream().anyMatch(t -> t.contains("Preview")),
            name + " shows a Source and a Preview tab; tabs seen: " + tabs);
        assertNull(MarkupEditor.getMarkupLanguagePreference(file),
            name + ": opening it stored no language on the file (nothing is written into the person's workspace)");
    }

    private void tablesRender(String name, String text) throws Exception {
        int expected = countPipeTables(text);
        assertTrue(expected > 0, name + " holds tables to render");
        open(copyIntoWorkspace(name, text));
        waitForTabs();
        String html = previewHtmlShown(expected);
        assertEquals(expected, count(html, "<table"), name + ": the preview shows " + count(html, "<table") + " of its " + expected + " tables");
    }

    /** Opens the file in a page with no other editor, the way a person double-clicks it; answers the editor's id. */
    private static String open(IFile file) {
        AtomicReference<String> editorId = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                // the Welcome page covers the editor area of a fresh workbench, as it does for a person on first start
                var intro = PlatformUI.getWorkbench().getIntroManager().getIntro();
                if (intro != null) { PlatformUI.getWorkbench().getIntroManager().closeIntro(intro); }
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                page.closeAllEditors(false);
                IEditorPart editor = IDE.openEditor(page, file);
                editorId.set(editor.getSite().getId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return editorId.get();
    }

    /** The editor builds its Source and Preview tabs once the workbench has laid it out: wait for them, up to ten seconds. */
    private static List<String> waitForTabs() throws InterruptedException {
        AtomicReference<List<String>> tabs = new AtomicReference<>(List.of());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Thread.sleep(200);
            Display.getDefault().syncExec(() -> tabs.set(tabTexts(shell())));
        } while (!(tabs.get().stream().anyMatch(t -> t.contains("Source")) && tabs.get().stream().anyMatch(t -> t.contains("Preview")))
            && System.nanoTime() < deadline);
        return tabs.get();
    }

    /** Selects the Preview tab as a click does, then reads what its browser shows, waiting up to ten seconds for the tables. */
    private static String previewHtmlShown(int tables) throws InterruptedException {
        Display.getDefault().syncExec(() -> {
            for (CTabFolder folder : folders(shell())) {
                for (CTabItem item : folder.getItems()) {
                    if (item.getText().contains("Preview")) {
                        folder.setSelection(item);
                        Event click = new Event();
                        click.item = item;
                        folder.notifyListeners(SWT.Selection, click);
                    }
                }
            }
        });
        AtomicReference<String> html = new AtomicReference<>("");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Thread.sleep(200);
            Display.getDefault().syncExec(() -> {
                Browser browser = browser(shell());
                assertNotNull(browser, "the Preview tab holds a browser");
                html.set(String.valueOf(browser.getText()));
            });
        } while (count(html.get(), "<table") < tables && System.nanoTime() < deadline);
        return html.get();
    }

    private static Shell shell() {
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
    }

    /** Tables written the way the specs write them: a header row followed by a |---| delimiter row. */
    private static int countPipeTables(String text) {
        int tables = 0;
        String[] lines = text.split("\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().matches("\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?") && lines[i - 1].trim().startsWith("|")) { tables++; }
        }
        return tables;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) { n++; }
        return n;
    }

    private static List<String> tabTexts(Shell shell) {
        List<String> texts = new ArrayList<>();
        for (CTabFolder folder : folders(shell)) {
            for (CTabItem item : folder.getItems()) { texts.add(item.getText()); }
        }
        return texts;
    }

    private static List<CTabFolder> folders(Control control) {
        List<CTabFolder> found = new ArrayList<>();
        if (control instanceof CTabFolder folder) { found.add(folder); }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) { found.addAll(folders(child)); }
        }
        return found;
    }

    private static Browser browser(Control control) {
        if (control instanceof Browser browser) { return browser; }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                Browser found = browser(child);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private static IFile copyIntoWorkspace(String name, String text) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("d6");
        if (!project.exists()) { project.create(null); }
        if (!project.isOpen()) { project.open(null); }
        IFile file = project.getFile(name);
        try (InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            if (file.exists()) { file.setContents(in, true, false, null); } else { file.create(in, true, null); }
        }
        return file;
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = FrameworkUtil.getBundle(WikiTextShowsSpecsTest.class).getEntry(path).openStream()) {
            return in.readAllBytes();
        }
    }
}
