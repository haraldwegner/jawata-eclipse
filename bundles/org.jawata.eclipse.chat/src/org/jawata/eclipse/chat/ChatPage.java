package org.jawata.eclipse.chat;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * The chat's page inside Eclipse (plan Stage 21, step 21.2): the page this bundle ships ({@code page/index.html}, built by
 * {@code scripts/build-chat-page.sh}) shown in an SWT Browser, and the one bridge between it and Java. The page speaks to its host
 * through {@code window.jawataHost.send(intent)}; this composite defines that function, after the page has loaded, over a Browser
 * function that hands each intent, as JSON text, to whoever listens. The first intent a page sends is {@code ready} with the view
 * contract number it was built against. A composite is the reusable part: a view shows it now, an editor-area page can later.
 */
public final class ChatPage extends Composite {

    /** What the page's own scripts wait for: the host function, then the event that tells the page to look for it. */
    private static final String HOST_SCRIPT = "window.jawataHost = { send: function (intent) { return jawataHostSend(JSON.stringify(intent)); } };"
        + " window.dispatchEvent(new Event('jawata-host-ready'));";

    private final Browser browser;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean loaded;

    /**
     * @param parent where the page is shown
     * @throws IllegalStateException if the bundle holds no page, which means the build skipped {@code scripts/build-chat-page.sh}
     */
    public ChatPage(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new FillLayout());
        browser = new Browser(this, SWT.NONE);
        new BrowserFunction(browser, "jawataHostSend") {
            @Override
            public Object function(Object[] arguments) {
                String intent = String.valueOf(arguments[0]);
                for (Consumer<String> listener : listeners) {
                    listener.accept(intent);
                }
                return null;
            }
        };
        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                loaded = true;
                browser.execute(HOST_SCRIPT);
            }
        });
        browser.setUrl(pageUrl());
    }

    /**
     * Asks to be handed every intent the page sends.
     *
     * @param listener receives one intent as JSON text, on the user interface thread
     */
    public void onIntent(Consumer<String> listener) {
        listeners.add(listener);
    }

    /**
     * Whether the page has finished loading and the host function is defined in it.
     *
     * @return true once the page's load completed
     */
    public boolean isLoaded() {
        return loaded;
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
