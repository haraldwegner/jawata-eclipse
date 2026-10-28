package org.jawata.eclipse.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The browser of the jawata terminal and its bridge to a {@link TerminalSession}. The page sends three messages (input, resize, ready) and
 * is sent one (write); the page keeps nothing the host needs. The session is made when the page says it is ready, so the shell starts at
 * the size the person's window really has.
 */
final class TerminalPage {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Browser browser;
    private final Display display;
    private final Supplier<TerminalSession> sessions;
    private volatile TerminalSession session;

    TerminalPage(Composite parent, Supplier<TerminalSession> sessions) {
        this.sessions = sessions;
        this.display = parent.getDisplay();
        this.browser = new Browser(parent, SWT.NONE);
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
                case "ready" -> start(node.path("cols").asInt(80), node.path("rows").asInt(24));
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
        TerminalSession open = session;
        session = null;
        if (open != null) {
            open.close();
        }
    }
}
