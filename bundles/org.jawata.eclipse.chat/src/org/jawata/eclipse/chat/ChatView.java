package org.jawata.eclipse.chat;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * The chat as a view (plan Stage 21; the editor-area placement is Stage 23): the person opens it from Window > Show View, and it
 * shows one {@link ChatPage}. It holds nothing else, so the page is the one place the chat lives.
 */
public final class ChatView extends ViewPart {

    /** The id plugin.xml gives the view, which a command or a test uses to show it. */
    public static final String ID = "org.jawata.eclipse.chat.view";

    private ChatPage page;

    @Override
    public void createPartControl(Composite parent) {
        page = new ChatPage(parent);
    }

    @Override
    public void setFocus() {
        if (page != null) {
            page.setFocus();
        }
    }

    /**
     * The page this view shows.
     *
     * @return the page, or null before the view's controls exist
     */
    public ChatPage page() {
        return page;
    }
}
