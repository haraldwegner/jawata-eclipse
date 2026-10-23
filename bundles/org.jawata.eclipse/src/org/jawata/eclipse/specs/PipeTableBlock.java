package org.jawata.eclipse.specs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.eclipse.mylyn.wikitext.parser.Attributes;
import org.eclipse.mylyn.wikitext.parser.DocumentBuilder.BlockType;
import org.eclipse.mylyn.wikitext.parser.TableCellAttributes;
import org.eclipse.mylyn.wikitext.parser.markup.Block;

/**
 * A run of lines starting with {@code |}: when commonmark reads it as one GitHub pipe table (a header row, then a delimiter row),
 * it is emitted as a table through WikiText's builder, each cell's own text through WikiText's inline Markdown; otherwise the lines
 * are emitted as the paragraph Markdown would have made of them. commonmark is asked only for the table's shape — rows, header
 * cells, alignment and where each cell's text sits in the source.
 *
 * <p>The shape is read once, on the first line, from the document text ahead of it; every row is then emitted while the parser
 * stands on that row's own line. WikiText's editor records where each block begins and needs those places in document order — a
 * table emitted after its last line put every cell past its own text and the editor refused to open (the 28k spec, C13).
 */
final class PipeTableBlock extends Block {

    private static final Parser TABLES = Parser.builder()
        .extensions(List.of(TablesExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build();

    /** Per line of the run (0 = the header row): its cells, or no entry for the delimiter row; null while undecided or not a table. */
    private Map<Integer, List<TableCell>> rows;
    private boolean paragraph;
    private int line = -1;

    @Override
    public boolean canStart(String text, int lineOffset) {
        String rest = text.substring(lineOffset);
        int indent = rest.length() - rest.stripLeading().length();
        return indent < 4 && rest.stripLeading().startsWith("|");   // four spaces would make it Markdown code
    }

    @Override
    protected int processLineContent(String text, int offset) {
        if (line < 0) {
            begin(text, offset);
        } else if (!text.substring(offset).stripLeading().startsWith("|")) {
            setClosed(true);
            return offset;
        }
        line++;
        if (paragraph) {
            if (line > 0) {
                builder.characters("\n");
            }
            getMarkupLanguage().emitMarkupLine(getParser(), state, text, offset);
        } else {
            emitRow(rows.get(line), text);
        }
        return -1;
    }

    @Override
    public void setClosed(boolean closed) {
        if (closed && !isClosed() && line >= 0) {
            builder.endBlock();   // the table, or the paragraph
        }
        super.setClosed(closed);
    }

    @Override
    public PipeTableBlock clone() {
        PipeTableBlock copy = (PipeTableBlock) super.clone();
        copy.rows = null;
        copy.paragraph = false;
        copy.line = -1;
        return copy;
    }

    /** Reads the run of pipe lines from here on and asks commonmark whether it is one table; opens the table or the paragraph. */
    private void begin(String text, int offset) {
        List<String> run = runFrom(text.substring(offset));
        Node first = TABLES.parse(String.join("\n", run)).getFirstChild();
        if (first instanceof TableBlock table && table.getNext() == null) {
            rows = cellsByLine(table);
            builder.beginBlock(BlockType.TABLE, new Attributes());
        } else {
            paragraph = true;
            builder.beginBlock(BlockType.PARAGRAPH, new Attributes());
        }
    }

    /** This line and the pipe lines that follow it in the document; just this line when the text ahead cannot be found. */
    private List<String> runFrom(String current) {
        List<String> run = new ArrayList<>();
        run.add(current);
        String content = state.getMarkupContent();
        int at = state.getLineDocumentOffset();
        if (content == null || at < 0 || at > content.length()) {
            return run;
        }
        int end = content.indexOf('\n', at);
        while (end >= 0) {
            int next = content.indexOf('\n', end + 1);
            String following = content.substring(end + 1, next < 0 ? content.length() : next).replace("\r", "");
            if (!following.stripLeading().startsWith("|")) {
                break;
            }
            run.add(following);
            end = next;
        }
        return run;
    }

    private static Map<Integer, List<TableCell>> cellsByLine(TableBlock table) {
        Map<Integer, List<TableCell>> byLine = new LinkedHashMap<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                List<SourceSpan> spans = row.getSourceSpans();
                if (spans.isEmpty()) {
                    continue;
                }
                List<TableCell> cells = new ArrayList<>();
                for (Node node = row.getFirstChild(); node != null; node = node.getNext()) {
                    if (node instanceof TableCell cell) {
                        cells.add(cell);
                    }
                }
                byLine.put(spans.get(0).getLineIndex(), cells);
            }
        }
        return byLine;
    }

    /** One row of the table, emitted on its own line; the delimiter row (no cells) emits nothing. */
    private void emitRow(List<TableCell> cells, String text) {
        if (cells == null) {
            return;
        }
        builder.beginBlock(BlockType.TABLE_ROW, new Attributes());
        for (TableCell cell : cells) {
            TableCellAttributes attributes = new TableCellAttributes();
            if (cell.getAlignment() != null) {
                attributes.setAlign(cell.getAlignment().name().toLowerCase(Locale.ROOT));
            }
            builder.beginBlock(cell.isHeader() ? BlockType.TABLE_CELL_HEADER : BlockType.TABLE_CELL_NORMAL, attributes);
            for (SourceSpan span : cell.getSourceSpans()) {
                String own = text.substring(span.getColumnIndex(), span.getColumnIndex() + span.getLength());
                if (!own.isBlank()) {
                    getMarkupLanguage().emitMarkupLine(getParser(), state, span.getColumnIndex(), own.strip(), 0);
                }
            }
            builder.endBlock();
        }
        builder.endBlock();
    }
}
