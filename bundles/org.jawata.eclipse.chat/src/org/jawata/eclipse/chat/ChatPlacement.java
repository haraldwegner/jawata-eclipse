package org.jawata.eclipse.chat;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.jawata.eclipse.access.EngineAddress;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * What the chat is wherever Eclipse places it (plan Stage 23, spec D10): one {@link ChatPage} served by one {@link ChatHost}, built in
 * the composite a view or an editor gives it. The view and the editor-area page differ only in the part that holds this, so the chat
 * itself exists once.
 */
final class ChatPlacement {

    private final ChatPage page;
    private final ChatHost host;

    /**
     * @param parent the composite of the view or editor that shows the chat
     * @param shell the window the chat's dialogs belong to
     * @param statusLine shows one line to the person in the part's status line; called on the user interface thread
     */
    ChatPlacement(Composite parent, Supplier<Shell> shell, Consumer<String> statusLine) {
        page = new ChatPage(parent);
        host = new ChatHost(page, new PreferencesChatSettings(), ChatPlacement::log, EngineAddress::url, new WorkbenchDialogs(shell, statusLine));
    }

    ChatPage page() {
        return page;
    }

    ChatHost host() {
        return host;
    }

    /** Gives the chat the keyboard focus, after bringing it up to date with a theme that may have changed while it was hidden. */
    void focus() {
        host.refreshTheme();
        page.setFocus();
    }

    /** Ends the chat's conversation and its page, when the part that holds it closes. */
    void close() {
        host.close();
    }

    private static void log(String message) {
        Bundle bundle = FrameworkUtil.getBundle(ChatPlacement.class);
        Platform.getLog(bundle).log(new Status(IStatus.INFO, bundle.getSymbolicName(), message));
    }
}
