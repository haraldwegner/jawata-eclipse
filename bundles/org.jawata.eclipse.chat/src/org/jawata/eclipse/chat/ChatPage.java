package org.jawata.eclipse.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The chat's page inside Eclipse (plan Stage 21): a page ({@code page/index.html}, built by {@code scripts/build-chat-page.sh}) shown
 * in an SWT Browser, and the one bridge between it and Java, two functions and nothing else. The page calls
 * {@code jawataHostCall(text)}, which runs the handler set with {@link #setHandler} on the user interface thread and gives the page its
 * answer at once; Java calls {@link #deliver}, which runs {@code jawataHostDeliver(text)} in the page. Both carry JSON text and this
 * class reads none of it. A composite is the reusable part: a view shows it now, an editor-area page can later.
 */
public final class ChatPage extends Composite {

    private final Browser browser;
    private volatile Function<String, String> handler = text -> null;
    private volatile boolean loaded;

    /**
     * Shows the page this bundle ships.
     *
     * @param parent where the page is shown
     * @throws IllegalStateException if the bundle holds no page, which means the build skipped {@code scripts/build-chat-page.sh}
     */
    public ChatPage(Composite parent) {
        this(parent, pageUrl());
    }

    /**
     * Shows another page over the same bridge, for a test.
     *
     * @param parent where the page is shown
     * @param url the page's address
     */
    public ChatPage(Composite parent, String url) {
        super(parent, SWT.NONE);
        setLayout(new FillLayout());
        browser = new Browser(this, SWT.NONE);
        new BrowserFunction(browser, "jawataHostCall") {
            @Override
            public Object function(Object[] arguments) {
                return handler.apply(String.valueOf(arguments[0]));
            }
        };
        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                loaded = true;
                browser.execute("window.dispatchEvent(new Event('jawata-host-ready'));");
            }
        });
        browser.setUrl(url);
        watchTheme();
    }

    /**
     * The stylesheet of the Eclipse theme as it is now (step 21.10): it sets the view's theme inputs from the surface this page sits on.
     *
     * @return the stylesheet, as text
     */
    public String themeCss() {
        return WidgetTheme.css(this);
    }

    /** Sends the page the theme as it is now, so an open chat is restyled at once. */
    public void refreshTheme() {
        if (isDisposed() || !loaded) {
            // a page not yet loaded asks for the theme itself when it starts
            return;
        }
        java.util.Map<String, String> event = new java.util.LinkedHashMap<>();
        event.put("kind", "theme");
        event.put("css", themeCss());
        try {
            deliver(new ObjectMapper().writeValueAsString(event));
        } catch (IOException unwritable) {
            // a map of two strings is always written
            throw new IllegalStateException(unwritable);
        }
    }

    /** The person changes the workbench theme or the system's settings: the page follows, and stops following when it goes away. */
    private void watchTheme() {
        org.eclipse.jface.util.IPropertyChangeListener changed = event -> refreshTheme();
        org.eclipse.swt.widgets.Listener settings = event -> refreshTheme();
        Display display = getDisplay();
        display.addListener(SWT.Settings, settings);
        org.eclipse.ui.themes.IThemeManager manager = org.eclipse.ui.PlatformUI.isWorkbenchRunning()
            ? org.eclipse.ui.PlatformUI.getWorkbench().getThemeManager() : null;
        if (manager != null) {
            manager.addPropertyChangeListener(changed);
        }
        addDisposeListener(event -> {
            display.removeListener(SWT.Settings, settings);
            if (manager != null) {
                manager.removePropertyChangeListener(changed);
            }
        });
    }

    /**
     * Sets who answers the page's calls.
     *
     * @param answer receives one call as JSON text, on the user interface thread, and returns the answer as JSON text, or null for none
     */
    public void setHandler(Function<String, String> answer) {
        this.handler = answer;
    }

    /**
     * Whether the page has finished loading.
     *
     * @return true once the page's load completed
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Tells the page something. May be called from any thread.
     *
     * @param message one event as JSON text
     */
    public void deliver(String message) {
        Display display = getDisplay();
        display.asyncExec(() -> {
            if (!isDisposed()) {
                browser.execute("window.jawataHostDeliver(" + quote(message) + ");");
            }
        });
    }

    /**
     * Text as a script string literal.
     *
     * @param text any text
     * @return the literal, quotes included
     */
    public static String quote(String text) {
        StringBuilder literal = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case ' ' -> literal.append("\\u2028");
                case ' ' -> literal.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        literal.append(String.format("\\u%04x", (int) c));
                    } else {
                        literal.append(c);
                    }
                }
            }
        }
        return literal.append('"').toString();
    }

    /**
     * The view contract number the page this bundle ships was built against.
     *
     * @return the number the build recorded in page/view-contract.json, or empty when the bundle holds no such record
     */
    public static Optional<Integer> builtContract() {
        URL record = FrameworkUtil.getBundle(ChatPage.class).getEntry("page/view-contract.json");
        if (record == null) {
            return Optional.empty();
        }
        try (InputStream in = record.openStream()) {
            JsonNode contract = new ObjectMapper().readTree(in).path("contract");
            return contract.isInt() ? Optional.of(contract.asInt()) : Optional.empty();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static String pageUrl() {
        Bundle bundle = FrameworkUtil.getBundle(ChatPage.class);
        URL folder = bundle.getEntry("page/");
        if (folder == null || bundle.getEntry("page/index.html") == null) {
            throw new IllegalStateException("The chat bundle holds no page/index.html: run scripts/build-chat-page.sh before the build.");
        }
        try {
            // the folder, not index.html alone: copying one file out of a packed bundle leaves chat.js behind
            return new URL(FileLocator.toFileURL(folder), "index.html").toString();
        } catch (IOException unreadable) {
            throw new IllegalStateException("The chat page cannot be read from the bundle: " + unreadable.getMessage(), unreadable);
        }
    }
}
