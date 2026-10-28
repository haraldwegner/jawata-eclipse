package org.jawata.eclipse.chat;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * The chat as a view (plan Stage 21): the person opens it from Window > Show View, and it shows the chat built by {@link ChatPlacement}.
 * The other placement, a page in the editor area, is {@link ChatEditor} (Stage 23).
 */
public final class ChatView extends ViewPart {

    /** The id plugin.xml gives the view, which a command or a test uses to show it. */
    public static final String ID = "org.jawata.eclipse.chat.view";

    private ChatPlacement chat;

    @Override
    public void createPartControl(Composite parent) {
        chat = new ChatPlacement(parent, () -> getSite().getShell(), message -> getViewSite().getActionBars().getStatusLineManager().setMessage(message));
    }

    @Override
    public void setFocus() {
        if (chat != null) {
            chat.focus();
        }
    }

    @Override
    public void dispose() {
        if (chat != null) {
            chat.close();
        }
        super.dispose();
    }

    /**
     * The page this view shows.
     *
     * @return the page, or null before the view's controls exist
     */
    public ChatPage page() {
        return chat == null ? null : chat.page();
    }

    /**
     * The host serving this view's page, through which an Eclipse command sends the chat a command.
     *
     * @return the host, or null before the view's controls exist
     */
    public ChatHost host() {
        return chat == null ? null : chat.host();
    }
}
