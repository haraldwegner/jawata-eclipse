package org.jawata.eclipse.chat;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.program.Program;

/**
 * Keeps a chat browser on the page it was given. Assistant answers carry links, and a page that loaded in its place would inherit the
 * bridge ({@code jawataHostCall} can start programs), so a link to the web opens in the system's browser and every other address is
 * refused; a window the page asks for is refused too. Only the main frame is watched: the chat view's sanitiser drops frames.
 */
final class NavigationLock {

    private NavigationLock() {
    }

    /**
     * Locks the browser to the folder the given page is in.
     *
     * @param browser the browser that shows the page
     * @param url the page's address
     */
    static void install(Browser browser, String url) {
        String folder = placeOf(url.substring(0, url.lastIndexOf('/') + 1));
        browser.addLocationListener(new LocationAdapter() {
            @Override
            public void changing(LocationEvent event) {
                String target = event.location;
                if (placeOf(target).startsWith(folder) || "about:blank".equals(target)) {
                    return;
                }
                event.doit = false;
                if (target.startsWith("https://") || target.startsWith("http://")) {
                    Program.launch(target);
                }
            }
        });
        // a window the page asks for is refused: with no browser set on the event, WebKit opens nothing
        browser.addOpenWindowListener(event -> event.browser = null);
    }

    /** An address with its file scheme spelled one way: Java writes {@code file:/x} and the browser {@code file:///x} for the same place. */
    static String placeOf(String address) {
        return address.startsWith("file:") ? "file:/" + address.substring("file:".length()).replaceFirst("^/+", "") : address;
    }
}
