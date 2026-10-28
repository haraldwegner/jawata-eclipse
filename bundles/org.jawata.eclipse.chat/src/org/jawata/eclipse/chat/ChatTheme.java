package org.jawata.eclipse.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The Eclipse theme, handed to the chat view (plan Stage 21, step 21.10; ledger 28i-C19-6). The view reads neutral inputs,
 * {@code --jc-host-<name>}, and knows no editor; this is the one place that says which value of Eclipse each input is, as
 * {@code packages/vscode/src/ui/theme.ts} does for VS Code. The values themselves are read from the running workbench by
 * {@link WidgetTheme}; here they are only named, so the map can be pinned input by input.
 */
public final class ChatTheme {

    /** What Eclipse is asked for. */
    public enum Value {
        /** The family of the workbench's default font. */
        DEFAULT_FONT,
        /** The size of the workbench's default font, in points. */
        FONT_SIZE,
        /** The family of the workbench's text (editor) font. */
        TEXT_FONT,
        /** The colour of text on a view's surface. */
        FOREGROUND,
        /** The colour of a view's surface. */
        BACKGROUND,
        /** The background of a list or tree: a surface raised from the view's. */
        LIST_BACKGROUND,
        /** The colour of a widget's border. */
        BORDER,
        /** The background of a text field. */
        FIELD_BACKGROUND,
        /** The colour of text in a text field. */
        FIELD_FOREGROUND,
        /** The background of a selected row: the accent. */
        SELECTION_BACKGROUND,
        /** The colour of text on a selected row. */
        SELECTION_FOREGROUND,
        /** The colour of a hyperlink. */
        LINK,
        /** The colour of an error. */
        ERROR,
        /** The colour of a warning. */
        WARNING,
        /** The colour of success. */
        SUCCESS,
        /** The background of the person's own messages: the accent, lightly, on the surface. */
        MINE_BACKGROUND,
        /** The colour of text on the person's own messages. */
        MINE_FOREGROUND
    }

    /** Which value of Eclipse each input of the view is; written down once more in the test on purpose, so a changed mapping is a decision. */
    public static final Map<String, Value> INPUTS = inputs();

    private ChatTheme() {
    }

    private static Map<String, Value> inputs() {
        Map<String, Value> inputs = new LinkedHashMap<>();
        inputs.put("font", Value.DEFAULT_FONT);
        inputs.put("size", Value.FONT_SIZE);
        inputs.put("mono", Value.TEXT_FONT);
        inputs.put("text", Value.FOREGROUND);
        inputs.put("ground", Value.BACKGROUND);
        inputs.put("raised", Value.LIST_BACKGROUND);
        inputs.put("edge", Value.BORDER);
        inputs.put("field", Value.FIELD_BACKGROUND);
        inputs.put("field-text", Value.FIELD_FOREGROUND);
        inputs.put("accent", Value.SELECTION_BACKGROUND);
        inputs.put("on-accent", Value.SELECTION_FOREGROUND);
        inputs.put("mine", Value.MINE_BACKGROUND);
        inputs.put("mine-text", Value.MINE_FOREGROUND);
        inputs.put("ok", Value.SUCCESS);
        inputs.put("bad", Value.ERROR);
        inputs.put("warn", Value.WARNING);
        inputs.put("link", Value.LINK);
        return Map.copyOf(inputs);
    }

    /**
     * A stylesheet, as text, that sets every input from the Eclipse value of its meaning.
     *
     * @param read gives the current text of a value, ready to be a CSS value (a colour as {@code #rrggbb}, a font as a quoted family,
     *             a size as {@code 12pt})
     * @return the stylesheet
     */
    public static String css(Function<Value, String> read) {
        StringBuilder css = new StringBuilder(":root {");
        new TreeMap<>(INPUTS).forEach((name, value) -> css.append(" --jc-host-").append(name).append(": ").append(read.apply(value)).append(';'));
        return css.append(" }").toString();
    }
}
