package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.eclipse.mylyn.wikitext.parser.MarkupParser;
import org.eclipse.mylyn.wikitext.parser.builder.HtmlDocumentBuilder;
import org.jawata.eclipse.specs.GfmTablesMarkdownLanguage;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Spec D6, his option B: jawata's Markdown renders the specs' pipe tables through WikiText's own parser and HTML builder, so the
 * cells keep WikiText's inline Markdown (code, bold) and the column alignment the delimiter row names.
 */
public class GfmTablesMarkdownLanguageTest {

    @Test
    public void theFixturesTwoTablesRenderAsTables() throws Exception {
        String html = html(fixture());
        assertEquals(2, count(html, "<table"), "the fixture's 2 pipe tables render as tables: " + html);
        assertEquals(0, count(html, "|---"), "no delimiter row is left as text: " + html);
    }

    @Test
    public void cellsKeepWikiTextsInlineMarkdown() throws Exception {
        String html = html(fixture());
        assertTrue(html.contains("<code>toolCount</code>"), "a code span in a cell renders as code: " + html);
        assertTrue(html.contains("<strong>the SPI bundle</strong>"), "bold in a cell renders as bold: " + html);
        assertTrue(html.contains("<th"), "the header row renders as header cells: " + html);
    }

    @Test
    public void theDelimiterRowsAlignmentReachesTheCells() {
        String html = html("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |\n");
        assertTrue(html.contains("align=\"left\""), html);
        assertTrue(html.contains("align=\"center\""), html);
        assertTrue(html.contains("align=\"right\""), html);
    }

    @Test
    public void pipeLinesWithoutADelimiterRowStayAParagraph() {
        String html = html("| not | a table |\n| still | not |\n");
        assertEquals(0, count(html, "<table"), html);
        assertTrue(html.contains("not"), html);
    }

    @Test
    public void textAroundATableKeepsItsOwnBlocks() {
        String html = html("A paragraph.\n| a | b |\n|---|---|\n| 1 | 2 |\n\nAfter.\n");
        assertEquals(1, count(html, "<table"), html);
        assertTrue(html.contains("<p>A paragraph.</p>") || html.contains("<p>A paragraph.\n</p>"), html);
        assertTrue(html.contains("After."), html);
    }

    private static String html(String markdown) {
        StringWriter out = new StringWriter();
        HtmlDocumentBuilder builder = new HtmlDocumentBuilder(out);
        builder.setEmitAsDocument(false);
        new MarkupParser(new GfmTablesMarkdownLanguage(), builder).parse(markdown);
        return out.toString();
    }

    private static String fixture() throws Exception {
        try (InputStream in = FrameworkUtil.getBundle(GfmTablesMarkdownLanguageTest.class).getEntry("resources/spec-tables.md").openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) { n++; }
        return n;
    }
}
