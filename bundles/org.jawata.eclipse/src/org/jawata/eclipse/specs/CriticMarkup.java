package org.jawata.eclipse.specs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec D14: the person's comments and suggested changes live in the {@code .md} file as plain-text CriticMarkup, so git shows them and an
 * agent reads them: a comment {@code {>> … <<}}, an addition {@code {++ … ++}}, a removal {@code {-- … --}}. This reads the marks, writes
 * them as inline HTML for WikiText to carry into the preview (WikiText stays the one renderer; nothing here draws), and computes the text
 * edit each of the five commands makes. Only the person accepts, rejects or resolves a mark; no method here does it by itself.
 *
 * <p>A mark inside a fenced code block or an inline code span, and a mark that is never closed, stays plain text.
 */
public final class CriticMarkup {

    /** The three marks. */
    public enum Kind {
        /** {@code {>> … <<}}. */
        COMMENT("{>>", "<<}", "(?s)\\{>>((?:(?!\\n[ \\t\\r]*\\n).)*?)<<\\}"),
        /** {@code {++ … ++}}. */
        ADDITION("{++", "++}", "(?s)\\{\\+\\+((?:(?!\\n[ \\t\\r]*\\n).)*?)\\+\\+\\}"),
        /** {@code {-- … --}}. */
        REMOVAL("{--", "--}", "(?s)\\{--((?:(?!\\n[ \\t\\r]*\\n).)*?)--\\}");

        private final String open;
        private final String close;
        private final Pattern pattern;

        Kind(String open, String close, String regex) {
            this.open = open;
            this.close = close;
            this.pattern = Pattern.compile(regex);
        }

        String wrap(String text) {
            return open + text + close;
        }
    }

    /**
     * One mark in a text.
     *
     * @param kind which mark
     * @param start the offset of its first character
     * @param end the offset after its last character
     * @param text what is written between its delimiters
     */
    public record Mark(Kind kind, int start, int end, String text) {
    }

    /**
     * A change to a text: the {@code length} characters at {@code offset} become {@code replacement}.
     *
     * @param offset where the change starts
     * @param length how many characters it replaces
     * @param replacement what takes their place
     */
    public record Edit(int offset, int length, String replacement) {

        /**
         * @param text the text this edit was computed for
         * @return the text with the edit made
         */
        public String applyTo(String text) {
            return text.substring(0, offset) + replacement + text.substring(offset + length);
        }
    }

    private static final Pattern FENCE = Pattern.compile("(?m)^(?: {0,3})(```|~~~).*$");
    private static final Pattern CODE_SPAN = Pattern.compile("(`+)(?:(?!\\1)[^\\n]|\\n(?!\\n))*?\\1");

    private CriticMarkup() {
    }

    /**
     * @param text a Markdown text
     * @return its marks in the order they appear; none inside code, none unclosed
     */
    public static List<Mark> marks(String text) {
        List<int[]> code = codeRanges(text);
        List<Mark> found = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            Matcher matcher = kind.pattern.matcher(text);
            while (matcher.find()) {
                if (!insideAny(code, matcher.start())) {
                    found.add(new Mark(kind, matcher.start(), matcher.end(), matcher.group(1)));
                }
            }
        }
        found.sort((a, b) -> Integer.compare(a.start(), b.start()));
        // a mark inside another mark is part of that mark's text, not a mark of its own
        List<Mark> apart = new ArrayList<>();
        int taken = 0;
        for (Mark mark : found) {
            if (mark.start() >= taken) {
                apart.add(mark);
                taken = mark.end();
            }
        }
        return apart;
    }

    /**
     * @param text a Markdown text
     * @param offset a caret offset, or the start of a selection
     * @return the mark the offset is in or touches the end of; empty when it is in none
     */
    public static Optional<Mark> markAt(String text, int offset) {
        return marks(text).stream().filter(mark -> mark.start() <= offset && offset <= mark.end()).findFirst();
    }

    /**
     * Writes every mark as inline HTML that WikiText's Markdown passes through to the preview. The attributes are styles, not a style sheet,
     * because the preview carries none of ours.
     *
     * @param text a Markdown text
     * @return the text with each mark rewritten; everything else is unchanged
     */
    public static String toHtml(String text) {
        List<Mark> marks = marks(text);
        StringBuilder out = new StringBuilder(text);
        for (int i = marks.size() - 1; i >= 0; i--) {
            Mark mark = marks.get(i);
            out.replace(mark.start(), mark.end(), html(mark));
        }
        return out.toString();
    }

    private static String html(Mark mark) {
        String escaped = mark.text().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return switch (mark.kind()) {
            case COMMENT -> "<span class=\"jawata-comment\" title=\"comment\" style=\"background:#fff3b0;border:1px solid #d9b800;border-radius:3px;padding:0 4px;font-size:90%\">&#128172; "
                + escaped.strip() + "</span>";
            case ADDITION -> "<ins class=\"jawata-addition\" title=\"suggested addition\" style=\"background:#d4f8d4;text-decoration:underline\">" + escaped + "</ins>";
            case REMOVAL -> "<del class=\"jawata-removal\" title=\"suggested removal\" style=\"background:#ffd7d5\">" + escaped + "</del>";
        };
    }

    /**
     * The "Comment" command: a comment mark after the selected text.
     *
     * @param selectionEnd the offset after the selected text
     * @param comment what the person wrote
     * @return the insertion
     */
    public static Edit comment(int selectionEnd, String comment) {
        return new Edit(selectionEnd, 0, Kind.COMMENT.wrap(comment));
    }

    /**
     * The "Suggest change" command: the selected text marked as removed, and an addition opened after it.
     *
     * @param text the text the selection is in
     * @param selectionStart the offset of the selection's first character
     * @param selectionEnd the offset after the selection
     * @param addition what the person wants in its place; may be empty, to be typed into the mark
     * @return the change
     */
    public static Edit suggestChange(String text, int selectionStart, int selectionEnd, String addition) {
        String selected = text.substring(selectionStart, selectionEnd);
        return new Edit(selectionStart, selectionEnd - selectionStart, Kind.REMOVAL.wrap(selected) + Kind.ADDITION.wrap(addition));
    }

    /**
     * Accepting: an addition becomes its text, a removal takes its text out, a comment is taken away.
     *
     * @param mark the mark to accept
     * @return the change
     */
    public static Edit accept(Mark mark) {
        return new Edit(mark.start(), mark.end() - mark.start(), mark.kind() == Kind.ADDITION ? mark.text() : "");
    }

    /**
     * Rejecting: an addition is taken away with its text, a removal leaves its text as it was, a comment is taken away.
     *
     * @param mark the mark to reject
     * @return the change
     */
    public static Edit reject(Mark mark) {
        return new Edit(mark.start(), mark.end() - mark.start(), mark.kind() == Kind.REMOVAL ? mark.text() : "");
    }

    /**
     * Resolving: a comment is taken away; the other two marks are the person's to accept or reject, so resolving them changes nothing.
     *
     * @param mark the mark to resolve
     * @return the change, empty when the mark is not a comment
     */
    public static Optional<Edit> resolve(Mark mark) {
        return mark.kind() == Kind.COMMENT ? Optional.of(new Edit(mark.start(), mark.end() - mark.start(), "")) : Optional.empty();
    }

    private static List<int[]> codeRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        Matcher fence = FENCE.matcher(text);
        int open = -1;
        String marker = null;
        while (fence.find()) {
            if (open < 0) {
                open = fence.start();
                marker = fence.group(1);
            } else if (fence.group(1).equals(marker)) {
                ranges.add(new int[] { open, fence.end() });
                open = -1;
            }
        }
        if (open >= 0) {
            ranges.add(new int[] { open, text.length() });
        }
        Matcher span = CODE_SPAN.matcher(text);
        while (span.find()) {
            if (!insideAny(ranges, span.start())) {
                ranges.add(new int[] { span.start(), span.end() });
            }
        }
        return ranges;
    }

    private static boolean insideAny(List<int[]> ranges, int offset) {
        for (int[] range : ranges) {
            if (range[0] <= offset && offset < range[1]) {
                return true;
            }
        }
        return false;
    }
}
