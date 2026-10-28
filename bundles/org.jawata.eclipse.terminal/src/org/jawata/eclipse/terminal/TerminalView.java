package org.jawata.eclipse.terminal;

import java.io.IOException;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.FrameworkUtil;

/**
 * The jawata terminal (plan Stage 13c, spec D7 as ruled by him on 2026-10-28): a view whose page is a terminal he types into and that draws
 * box lines and Unicode properly. It sits beside Eclipse's own Terminal and does not replace it. This step shows the page; the shell behind
 * it comes in the next steps (see ARCHITECTURE-terminal.md).
 */
public final class TerminalView extends ViewPart {

    /** The id plugin.xml gives the view, which a command or a test uses to show it. */
    public static final String ID = "org.jawata.eclipse.terminal.view";

    private Browser browser;

    @Override
    public void createPartControl(Composite parent) {
        browser = new Browser(parent, SWT.NONE);
        try {
            // the whole page folder, not index.html alone: Eclipse extracts only what it is asked for, and the scripts sit beside the page
            browser.setUrl(FileLocator.toFileURL(FrameworkUtil.getBundle(TerminalView.class).getEntry("page/")) + "index.html");
        } catch (IOException unreadable) {
            browser.setText("<p>The jawata terminal's page could not be read: " + unreadable.getMessage() + "</p>");
        }
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        }
    }

    /**
     * The page this view shows.
     *
     * @return the browser, or null before the view's controls exist
     */
    public Browser browser() {
        return browser;
    }
}
