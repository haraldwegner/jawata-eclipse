package org.jawata.eclipse.specs;

import java.util.List;

import org.eclipse.mylyn.wikitext.markdown.MarkdownLanguage;
import org.eclipse.mylyn.wikitext.parser.MarkupParser;
import org.eclipse.mylyn.wikitext.parser.markup.Block;

/**
 * Spec D6 (his option B): WikiText's Markdown, plus the GitHub pipe tables the sprint documents are written with. WikiText stays
 * the one renderer — its builder writes every byte of the preview — and commonmark's table parser only reads a table's shape.
 * Public with a no-argument constructor: WikiText clones a language through that constructor for its preview.
 */
public class GfmTablesMarkdownLanguage extends MarkdownLanguage {

    /**
     * Spec D14: the person's comments and suggested changes are written as HTML before WikiText reads the text, so the preview shows them
     * as callouts with no renderer of ours (WikiText's Markdown passes inline HTML through). Only for the preview, which builds HTML: the
     * editor's own reads of the same text (its outline and its source colouring) must see every character where it is, and the rewrite
     * makes the text longer, which sent WikiText's source partitioner outside the document.
     */
    @Override
    public void processContent(MarkupParser parser, String markupContent, boolean asDocument) {
        boolean forThePreview = parser.getBuilder() instanceof org.eclipse.mylyn.wikitext.parser.builder.HtmlDocumentBuilder;
        super.processContent(parser, forThePreview ? CriticMarkup.toHtml(markupContent) : markupContent, asDocument);
    }

    /** The table block goes first, and it may end a paragraph: a table directly under a line of text is still a table. */
    @Override
    protected void addStandardBlocks(List<Block> blocks, List<Block> paragraphBreakingBlocks) {
        super.addStandardBlocks(blocks, paragraphBreakingBlocks);
        PipeTableBlock tables = new PipeTableBlock();
        blocks.add(0, tables);
        paragraphBreakingBlocks.add(0, tables);
    }
}
