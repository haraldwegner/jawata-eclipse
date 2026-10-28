package org.jawata.eclipse.chat;

import java.util.function.Function;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * Reads the value of Eclipse each chat theme input stands for (plan Stage 21, step 21.10) from the running workbench: the colours of the
 * surface the chat is shown on and of probe widgets (a text field, a tree) that Eclipse's theme has styled, and its fonts. Which input is
 * which value is {@link ChatTheme}'s; this class only answers what a value is now, as CSS text. Two values are not Eclipse's own and
 * are said so here: the border and the person's own messages are mixed from the surface's colours so they follow any theme, and the
 * success and warning colours are fixed, because Eclipse has no theme colour for either.
 */
final class WidgetTheme implements Function<ChatTheme.Value, String> {

    /** Eclipse has no theme colour for success; the view's own green reads on a light and a dark surface. */
    static final String SUCCESS = "#2e9d57";

    /** Eclipse has no theme colour for a warning; the view's own amber reads on a light and a dark surface. */
    static final String WARNING = "#c98a1a";

    private final Composite surface;
    private final Text field;
    private final Tree list;

    private WidgetTheme(Composite surface, Text field, Tree list) {
        this.surface = surface;
        this.field = field;
        this.list = list;
    }

    /**
     * The stylesheet of the theme as it is now. Must be called on the user interface thread.
     *
     * @param surface the composite the chat is shown on, whose colours are the chat's surface
     * @return the stylesheet, as text
     */
    static String css(Composite surface) {
        Shell probe = new Shell(surface.getDisplay(), SWT.NO_TRIM);
        try {
            Composite holder = new Composite(probe, SWT.NONE);
            return ChatTheme.css(new WidgetTheme(surface, new Text(holder, SWT.BORDER), new Tree(holder, SWT.NONE)));
        } finally {
            probe.dispose();
        }
    }

    @Override
    public String apply(ChatTheme.Value value) {
        Display display = surface.getDisplay();
        return switch (value) {
            case DEFAULT_FONT -> family(JFaceResources.getDefaultFont().getFontData());
            case FONT_SIZE -> JFaceResources.getDefaultFont().getFontData()[0].getHeight() + "pt";
            case TEXT_FONT -> family(JFaceResources.getTextFont().getFontData());
            case FOREGROUND -> hex(surface.getForeground());
            case BACKGROUND -> hex(surface.getBackground());
            case LIST_BACKGROUND -> hex(list.getBackground());
            case BORDER -> hex(mix(surface.getForeground().getRGB(), surface.getBackground().getRGB(), 0.22));
            case FIELD_BACKGROUND -> hex(field.getBackground());
            case FIELD_FOREGROUND -> hex(field.getForeground());
            case SELECTION_BACKGROUND -> hex(display.getSystemColor(SWT.COLOR_LIST_SELECTION));
            case SELECTION_FOREGROUND -> hex(display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT));
            case LINK -> hex(registered(JFacePreferences.HYPERLINK_COLOR, display.getSystemColor(SWT.COLOR_LINK_FOREGROUND)));
            case ERROR -> hex(registered(JFacePreferences.ERROR_COLOR, display.getSystemColor(SWT.COLOR_RED)));
            case WARNING -> WARNING;
            case SUCCESS -> SUCCESS;
            case MINE_BACKGROUND -> hex(mix(display.getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB(), surface.getBackground().getRGB(), 0.25));
            case MINE_FOREGROUND -> hex(surface.getForeground());
        };
    }

    private static Color registered(String key, Color otherwise) {
        Color found = JFaceResources.getColorRegistry().get(key);
        return found == null ? otherwise : found;
    }

    /** The family of the first font as a quoted CSS family, with a generic one behind it. */
    private static String family(FontData[] fonts) {
        return "\"" + fonts[0].getName().replace("\"", "") + "\", sans-serif";
    }

    /** A colour as {@code #rrggbb}. */
    static String hex(Color color) {
        return hex(color.getRGB());
    }

    /** A colour as {@code #rrggbb}. */
    static String hex(RGB rgb) {
        return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
    }

    /**
     * Mixes two colours.
     *
     * @param over the colour laid on
     * @param under the colour it is laid on
     * @param share how much of {@code over} is kept, from 0 to 1
     * @return the mixed colour
     */
    static RGB mix(RGB over, RGB under, double share) {
        return new RGB(
            (int) Math.round(over.red * share + under.red * (1 - share)),
            (int) Math.round(over.green * share + under.green * (1 - share)),
            (int) Math.round(over.blue * share + under.blue * (1 - share)));
    }
}
