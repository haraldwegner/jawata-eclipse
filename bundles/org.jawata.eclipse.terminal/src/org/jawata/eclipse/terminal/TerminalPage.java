package org.jawata.eclipse.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.jawata.eclipse.look.EclipseLook;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The browser of the jawata terminal and its bridge to a {@link TerminalSession}. The page sends three messages (input, resize, ready) and
 * is sent one (write); the page keeps nothing the host needs. The session is made when the page says it is ready and is told the page's
 * size straight after it starts; the shell is already running by then, so a program that read the size at its very first instruction saw the
 * pseudo-terminal's default, and one that waits for the size change (a shell redrawing its prompt, a full-screen program) gets the real one.
 */
final class TerminalPage {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Browser browser;
    private final Display display;
    private final Supplier<TerminalSession> sessions;
    private volatile TerminalSession session;
    private final IPropertyChangeListener editorFont;

    TerminalPage(Composite parent, Supplier<TerminalSession> sessions) {
        this.sessions = sessions;
        this.display = parent.getDisplay();
        this.browser = new Browser(parent, SWT.NONE);
        this.editorFont = event -> {
            if (JFaceResources.TEXT_FONT.equals(event.getProperty())) {
                display.asyncExec(this::applyLook);
            }
        };
        JFaceResources.getFontRegistry().addListener(editorFont);
        new BrowserFunction(browser, "jawataTerminalHost") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String message) {
                    handle(message);
                }
                return null;
            }
        };
        try {
            // the whole page folder, not index.html alone: Eclipse extracts only what it is asked for, and the scripts sit beside the page
            browser.setUrl(FileLocator.toFileURL(FrameworkUtil.getBundle(TerminalPage.class).getEntry("page/")) + "index.html");
        } catch (IOException unreadable) {
            browser.setText("<p>The jawata terminal's page could not be read: " + unreadable.getMessage() + "</p>");
        }
    }

    private void handle(String message) {
        try {
            JsonNode node = JSON.readTree(message);
            switch (node.path("type").asText()) {
                case "ready" -> {
                    // not called here: a script run from inside the page's own call to the host waits on that call, and WebKit gives up after 10 s
                    display.asyncExec(this::applyLook);
                    start(node.path("cols").asInt(80), node.path("rows").asInt(24));
                }
                case "input" -> {
                    TerminalSession open = session;
                    if (open != null) {
                        open.write(node.path("data").asText().getBytes(StandardCharsets.UTF_8));
                    }
                }
                case "resize" -> {
                    TerminalSession open = session;
                    if (open != null) {
                        open.resize(node.path("cols").asInt(80), node.path("rows").asInt(24));
                    }
                }
                default -> {
                    // a message this host does not know is ignored
                }
            }
        } catch (IOException | RuntimeException failed) {
            show("\r\n[the jawata terminal could not do that: " + failed.getMessage() + "]\r\n");
        }
    }

    private void start(int columns, int rows) {
        if (session != null) {
            return;
        }
        TerminalSession started = sessions.get();
        started.onOutput(bytes -> display.asyncExec(() -> write(bytes)));
        session = started;
        started.resize(columns, rows);
    }

    /** Tells the page what Eclipse looks like now: the surface's colours and the editor text font. Runs on the user interface thread. */
    private void applyLook() {
        if (browser.isDisposed()) {
            return;
        }
        EclipseLook look = EclipseLook.of(browser.getParent());
        try {
            browser.execute("jawataTerminal.look(" + JSON.writeValueAsString(Map.of("fontFamily", look.textFamily(), "fontSize", look.textPixels(),
                "background", look.background(), "foreground", look.foreground())) + ");");
        } catch (IOException unwritable) {
            // the page keeps the look it has: a look that cannot be written is not worth stopping the terminal for
        }
    }

    private void write(byte[] bytes) {
        if (!browser.isDisposed()) {
            browser.execute("jawataTerminal.write('" + Base64.getEncoder().encodeToString(bytes) + "');");
        }
    }

    private void show(String text) {
        display.asyncExec(() -> write(text.getBytes(StandardCharsets.UTF_8)));
    }

    Browser browser() {
        return browser;
    }

    void focus() {
        if (!browser.isDisposed()) {
            browser.setFocus();
            browser.execute("jawataTerminal.focus();");
        }
    }

    void close() {
        JFaceResources.getFontRegistry().removeListener(editorFont);
        TerminalSession open = session;
        session = null;
        if (open != null) {
            open.close();
        }
    }
}
