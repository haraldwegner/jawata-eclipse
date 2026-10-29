package org.jawata.eclipse.look;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;

/**
 * What Eclipse looks like right now on the surface a jawata view is shown on: the surface's colours and the editor text font. The terminal
 * puts these in its page, so it follows the theme and the font the person chose in Eclipse; this is the one place that reads them. The chat
 * keeps its own reading of the theme ({@code WidgetTheme}): the chat bundle's contract imports exactly one jawata package, so it cannot use
 * this one. Must be created on the user interface thread.
 *
 * @param background the surface's colour, as {@code #rrggbb}
 * @param foreground the colour of text on the surface, as {@code #rrggbb}
 * @param textFamily the family of the editor text font
 * @param textPoints the size of the editor text font, in points
 */
public record EclipseLook(String background, String foreground, String textFamily, int textPoints) {

    /**
     * Reads the look of a surface.
     *
     * @param surface the composite a view is shown on
     * @return its colours and the editor text font
     */
    public static EclipseLook of(Composite surface) {
        FontData text = JFaceResources.getTextFont().getFontData()[0];
        return new EclipseLook(hex(surface.getBackground()), hex(surface.getForeground()), text.getName(), text.getHeight());
    }

    /**
     * The size of the text font as a browser page counts it.
     *
     * @return the size in CSS pixels, at 96 to the inch
     */
    public int textPixels() {
        return (int) Math.round(textPoints * 96.0 / 72.0);
    }

    /**
     * A colour as text.
     *
     * @param color the colour
     * @return {@code #rrggbb}
     */
    public static String hex(Color color) {
        return hex(color.getRGB());
    }

    /**
     * A colour as text.
     *
     * @param rgb the colour
     * @return {@code #rrggbb}
     */
    public static String hex(RGB rgb) {
        return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
    }
}
