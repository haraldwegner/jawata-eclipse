package org.jawata.eclipse.chat;

import java.io.IOException;
import java.util.Map;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExecutableExtension;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A view of the chat's right stack (Sprint 28h, D53 (r)): the shared page in the role plugin.xml names, Agent Control or btw. It holds no conversation
 * of its own; what it hears and asks goes to the chat's page through {@link ChatHost}, and the chat is opened when this view is the first to open.
 */
public final class ChatPanelView extends ViewPart implements IExecutableExtension {

    /** The id plugin.xml gives the btw view. */
    static final String BTW_ID = "org.jawata.eclipse.chat.btw";

    private final ObjectMapper json = new ObjectMapper();
    private String role = "agents";
    private ChatPage page;

    @Override
    public void setInitializationData(IConfigurationElement config, String propertyName, Object data) {
        if (data instanceof String given) {
            role = given;
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        page = new ChatPage(parent, ChatPage.panelUrl(role));
        page.setHandler(this::handle);
    }

    private String handle(String call) {
        try {
            JsonNode op = json.readTree(call);
            switch (op.path("op").asText()) {
                case "theme":
                    return json.writeValueAsString(Map.of("css", WidgetTheme.css(page)));
                case "panelReady":
                    ChatHost.panelJoined(role, page);
                    openTheChat();
                    return null;
                case "fromPanel":
                    ChatHost.panelAsked(role, op.path("intent"));
                    return null;
                default:
                    return null;
            }
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The panel has nothing to show without the chat, so the chat is created, in the background, if it is not open yet. */
    private void openTheChat() {
        page.getDisplay().asyncExec(() -> {
            try {
                getSite().getPage().showView(ChatView.ID, null, IWorkbenchPage.VIEW_CREATE);
            } catch (PartInitException cannotOpen) {
                // the panel stays empty and the error is in the workbench's own log
            }
        });
    }

    @Override
    public void setFocus() {
        if (page != null) {
            page.setFocus();
        }
    }

    @Override
    public void dispose() {
        ChatHost.panelLeft(role);
        super.dispose();
    }
}
