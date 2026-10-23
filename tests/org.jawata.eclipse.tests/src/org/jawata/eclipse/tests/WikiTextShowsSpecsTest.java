package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.mylyn.wikitext.markdown.MarkdownLanguage;
import org.eclipse.mylyn.wikitext.parser.MarkupParser;
import org.eclipse.mylyn.wikitext.parser.builder.HtmlDocumentBuilder;
import org.eclipse.mylyn.wikitext.parser.markup.MarkupLanguage;
import org.eclipse.mylyn.wikitext.ui.WikiText;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Spec D6: a sprint document opens in Eclipse with its source and a preview, and the preview shows the specs' tables. The file is a
 * copy of the bundled spec-shaped fixture, and of a real spec when the build is given one with -Djawata.spec.md=PATH (the specs stay
 * out of this public repository). The preview's HTML is what the language WikiText maps the file name to renders for the preview tab.
 */
public class WikiTextShowsSpecsTest {

    private static final String MARKUP_EDITOR = "org.eclipse.mylyn.wikitext.ui.editor.markupEditor";

    /** D6's table half: red until his decision (WikiText's Markdown renders no tables); the default build leaves it out, -Pd6-tables runs it. */
    static final String TABLES = "d6-tables";

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
    @Tag(TABLES)
    public void theFixturesTablesRenderInThePreview() throws Exception {
        tablesRender("spec-tables.md", fixture());
    }

    @Test
    @Tag(TABLES)
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
        AtomicReference<String> editorId = new AtomicReference<>();
        AtomicReference<List<String>> tabs = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                // the Welcome page covers the editor area of a fresh workbench, as it does for a person on first start
                var intro = PlatformUI.getWorkbench().getIntroManager().getIntro();
                if (intro != null) { PlatformUI.getWorkbench().getIntroManager().closeIntro(intro); }
                IEditorPart editor = IDE.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), file);
                editorId.set(editor.getSite().getId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // the editor builds its Source and Preview tabs once the workbench has laid it out: wait for them, up to ten seconds
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            Thread.sleep(200);
            Display.getDefault().syncExec(() -> tabs.set(tabTexts(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell())));
        } while (!(tabs.get().stream().anyMatch(t -> t.contains("Source")) && tabs.get().stream().anyMatch(t -> t.contains("Preview"))) && System.nanoTime() < deadline);
        assertEquals(MARKUP_EDITOR, editorId.get(), name + " opens in WikiText's markup editor");
        assertTrue(tabs.get().stream().anyMatch(t -> t.contains("Source")) && tabs.get().stream().anyMatch(t -> t.contains("Preview")),
            name + " shows a Source and a Preview tab; tabs seen: " + tabs.get());
    }

    private void tablesRender(String name, String text) {
        int expected = countPipeTables(text);
        String html = previewHtml(name, text);
        int rendered = count(html, "<table");
        assertTrue(expected > 0, name + " holds tables to render");
        assertEquals(expected, rendered, name + ": the preview renders " + rendered + " of its " + expected + " tables");
    }

    /** The HTML the language WikiText maps this file name to makes of the text: what the editor's preview tab shows. */
    private static String previewHtml(String name, String text) {
        MarkupLanguage language = WikiText.getMarkupLanguageForFilename(name);
        assertTrue(language instanceof MarkdownLanguage, name + " is read as Markdown by WikiText; it maps to " + language);
        StringWriter out = new StringWriter();
        new MarkupParser(language, new HtmlDocumentBuilder(out)).parse(text);
        return out.toString();
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
        collect(shell, texts);
        return texts;
    }

    private static void collect(Control control, List<String> texts) {
        if (control instanceof CTabFolder folder) {
            for (CTabItem item : folder.getItems()) { texts.add(item.getText()); }
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) { collect(child, texts); }
        }
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
