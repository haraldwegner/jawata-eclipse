package org.jawata.eclipse.chat;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;
import org.jawata.eclipse.access.EngineAddress;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * The chat as a view (plan Stage 21; the editor-area placement is Stage 23): the person opens it from Window > Show View, and it
 * shows one {@link ChatPage} served by one {@link ChatHost}. It holds nothing else, so the page is the one place the chat lives.
 */
public final class ChatView extends ViewPart {

    /** The id plugin.xml gives the view, which a command or a test uses to show it. */
    public static final String ID = "org.jawata.eclipse.chat.view";

    private ChatPage page;
    private ChatHost host;

    @Override
    public void createPartControl(Composite parent) {
        page = new ChatPage(parent);
        host = new ChatHost(page, new PreferencesChatSettings(), ChatView::log, EngineAddress::url,
            new WorkbenchDialogs(() -> getSite().getShell(), message -> getViewSite().getActionBars().getStatusLineManager().setMessage(message)));
    }

    @Override
    public void setFocus() {
        if (page != null) {
            page.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (host != null) {
            host.close();
        }
        super.dispose();
    }

    /**
     * The page this view shows.
     *
     * @return the page, or null before the view's controls exist
     */
    public ChatPage page() {
        return page;
    }

    /**
     * The host serving this view's page, through which an Eclipse command sends the chat a command.
     *
     * @return the host, or null before the view's controls exist
     */
    public ChatHost host() {
        return host;
    }

    private static void log(String message) {
        Bundle bundle = FrameworkUtil.getBundle(ChatView.class);
        Platform.getLog(bundle).log(new Status(IStatus.INFO, bundle.getSymbolicName(), message));
    }
}
