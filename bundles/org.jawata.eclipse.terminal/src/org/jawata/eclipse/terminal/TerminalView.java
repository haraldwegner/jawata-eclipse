package org.jawata.eclipse.terminal;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * The jawata terminal (plan Stage 13c, spec D7 as ruled by him on 2026-10-28): a view whose page is a terminal he types into and that draws
 * box lines and Unicode properly. It sits beside Eclipse's own Terminal and does not replace it. The page is xterm.js; behind it runs the
 * person's own shell on a pseudo-terminal (see ARCHITECTURE-terminal.md).
 */
public final class TerminalView extends ViewPart {

    /** The id plugin.xml gives the view, which a command or a test uses to show it. */
    public static final String ID = "org.jawata.eclipse.terminal.view";

    private static volatile Supplier<TerminalSession> sessions = TerminalView::shell;

    private TerminalPage page;

    /**
     * Puts another way of making the shell behind the page, for a test that must not start a real one.
     *
     * @param replacement makes the session a newly shown terminal talks to
     * @return what puts the person's own shell back
     */
    public static Runnable sessionsWith(Supplier<TerminalSession> replacement) {
        Supplier<TerminalSession> before = sessions;
        sessions = replacement;
        return () -> sessions = before;
    }

    private static TerminalSession shell() {
        try {
            return new PtyShell(PtyShell.defaultShell(), new File(System.getProperty("user.home")));
        } catch (IOException cannotStart) {
            throw new IllegalStateException("the shell could not be started: " + cannotStart.getMessage(), cannotStart);
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        page = new TerminalPage(parent, () -> sessions.get());
    }

    @Override
    public void setFocus() {
        if (page != null) {
            page.focus();
        }
    }

    @Override
    public void dispose() {
        if (page != null) {
            page.close();
        }
        super.dispose();
    }

    /**
     * The browser this view shows.
     *
     * @return the browser, or null before the view's controls exist
     */
    public Browser browser() {
        return page == null ? null : page.browser();
    }
}
