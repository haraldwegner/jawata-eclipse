package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.eclipse.specs.CriticMarkup;
import org.jawata.eclipse.specs.CriticMarkup.Kind;
import org.jawata.eclipse.specs.CriticMarkup.Mark;
import org.junit.jupiter.api.Test;

/** Spec D14, plan Stage 13b: the three marks are read, kept as text where they are not marks, and the five commands edit the text and nothing else. */
public class CriticMarkupTest {

    @Test
    public void eachOfTheThreeMarksIsRead() {
        String text = "a {>>why?<<} b {++new++} c {--old--} d";
        List<Mark> marks = CriticMarkup.marks(text);
        assertEquals(List.of(Kind.COMMENT, Kind.ADDITION, Kind.REMOVAL), marks.stream().map(Mark::kind).toList());
        assertEquals(List.of("why?", "new", "old"), marks.stream().map(Mark::text).toList());
        assertEquals("{>>why?<<}", text.substring(marks.get(0).start(), marks.get(0).end()));
    }

    @Test
    public void aMarkInsideACodeBlockOrASpanStaysText() {
        String fenced = "```\n{>>not a comment<<}\n```\n";
        assertEquals(List.of(), CriticMarkup.marks(fenced));
        assertEquals(List.of(), CriticMarkup.marks("the form is `{++x++}` in code"));
        assertEquals(1, CriticMarkup.marks("```\ncode\n```\n{>>real<<}").size());
    }

    @Test
    public void anUnclosedMarkStaysText() {
        assertEquals(List.of(), CriticMarkup.marks("a {>>never closed and {++ also not"));
        assertEquals("a {>>never closed", CriticMarkup.toHtml("a {>>never closed"));
    }

    /** A stray opener ends at the blank line in a file with Unix line breaks and in one with Windows line breaks alike (ledger 28k-A13b-4). */
    @Test
    public void anUnclosedOpenerDoesNotRunAcrossAParagraphWhateverTheLineEndings() {
        String unix = "start {>>never closed\n\nnext paragraph {>>real<<} end";
        assertEquals(List.of("real"), CriticMarkup.marks(unix).stream().map(Mark::text).toList(), "with Unix line breaks");
        assertEquals(List.of("real"), CriticMarkup.marks(unix.replace("\n", "\r\n")).stream().map(Mark::text).toList(), "with Windows line breaks");
    }

    @Test
    public void marksBecomeInlineHtmlAndTheRestIsUnchanged() {
        String html = CriticMarkup.toHtml("keep {>>a < b<<} this {++add++}{--drop--} end");
        assertTrue(html.startsWith("keep <span class=\"jawata-comment\""), html);
        assertTrue(html.contains("a &lt; b</span>"), "the comment's text is escaped: " + html);
        assertTrue(html.contains("<ins class=\"jawata-addition\""), html);
        assertTrue(html.contains(">add</ins>"), html);
        assertTrue(html.contains("<del class=\"jawata-removal\""), html);
        assertTrue(html.endsWith(" end"), html);
        assertFalse(html.contains("{>>") || html.contains("{++") || html.contains("{--"), html);
    }

    @Test
    public void commentInsertsAMarkAfterTheSelection() {
        String text = "Make this clearer, please.";
        String result = CriticMarkup.comment(text.indexOf("clearer") + "clearer".length(), "which part?").applyTo(text);
        assertEquals("Make this clearer{>>which part?<<}, please.", result);
    }

    @Test
    public void suggestChangeMarksTheSelectionRemovedAndOpensAnAdditionAfterIt() {
        String text = "Use the old name here.";
        int start = text.indexOf("old");
        String result = CriticMarkup.suggestChange(text, start, start + 3, "new").applyTo(text);
        assertEquals("Use the {--old--}{++new++} name here.", result);
        assertEquals("Use the {--old--}{++++} name here.", CriticMarkup.suggestChange(text, start, start + 3, "").applyTo(text));
    }

    @Test
    public void acceptingAnAdditionWritesItsTextAndTheMarkIsGone() {
        String text = "a {++new++} b";
        Mark mark = CriticMarkup.markAt(text, 5).orElseThrow();
        assertEquals("a new b", CriticMarkup.accept(mark).applyTo(text));
    }

    @Test
    public void rejectingAnAdditionRemovesTheMarkAndLeavesTheTextAsItWas() {
        String text = "a {++new++} b";
        Mark mark = CriticMarkup.markAt(text, 5).orElseThrow();
        assertEquals("a  b", CriticMarkup.reject(mark).applyTo(text));
    }

    @Test
    public void aRemovalIsTheMirrorOfAnAddition() {
        String text = "a {--old--} b";
        Mark mark = CriticMarkup.markAt(text, 5).orElseThrow();
        assertEquals("a  b", CriticMarkup.accept(mark).applyTo(text));
        assertEquals("a old b", CriticMarkup.reject(mark).applyTo(text));
    }

    @Test
    public void resolvingACommentRemovesItAndLeavesTheOtherMarksToThePerson() {
        String text = "a {>>note<<} b {++x++}";
        Mark comment = CriticMarkup.markAt(text, 4).orElseThrow();
        assertEquals("a  b {++x++}", CriticMarkup.resolve(comment).orElseThrow().applyTo(text));
        Mark addition = CriticMarkup.markAt(text, text.length() - 2).orElseThrow();
        assertTrue(CriticMarkup.resolve(addition).isEmpty(), "an addition is accepted or rejected, not resolved");
    }

    @Test
    public void everyCommandChangesOnlyTheBytesOfItsOwnMark() {
        String text = "before {>>c<<} between {++a++} after";
        Mark mark = CriticMarkup.markAt(text, text.indexOf("{++") + 1).orElseThrow();
        String accepted = CriticMarkup.accept(mark).applyTo(text);
        assertTrue(accepted.startsWith(text.substring(0, mark.start())), accepted);
        assertTrue(accepted.endsWith(text.substring(mark.end())), accepted);
    }

    @Test
    public void aMarkDoesNotRunOnAcrossABlankLine() {
        List<Mark> marks = CriticMarkup.marks("stray {>>never closed\n\nnext paragraph {>>real<<} end");
        assertEquals(1, marks.size(), "the stray opening does not swallow the paragraph after it: " + marks);
        assertEquals("real", marks.get(0).text());
    }

    @Test
    public void aMarkInsideAnotherMarkIsPartOfItsText() {
        String text = "a {++x {>>c<<} y++} b";
        List<Mark> marks = CriticMarkup.marks(text);
        assertEquals(1, marks.size(), marks.toString());
        assertEquals(Kind.ADDITION, marks.get(0).kind());
        assertTrue(CriticMarkup.toHtml(text).endsWith("</ins> b"), CriticMarkup.toHtml(text));
    }

    @Test
    public void aCaretOutsideEveryMarkFindsNone() {
        assertTrue(CriticMarkup.markAt("plain {>>c<<} text", 1).isEmpty());
    }
}
