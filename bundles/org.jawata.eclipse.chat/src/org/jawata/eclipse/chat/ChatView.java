package org.jawata.eclipse.chat;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

/**
 * The chat as a view (plan Stage 21): the person opens it from Window > Show View, and it shows the chat built by {@link ChatPlacement}.
 * A chat that opens for the first time goes where the person chose, in the middle (the editor area) or in the side panel, as
 * {@link ChatPlacementChoice} says (Stage 23, his pick of 2026-10-28). A chat that is restored after a restart, or that the person has
 * dragged somewhere, stays where it is.
 */
public final class ChatView extends ViewPart {

    /** The id plugin.xml gives the view, which a command or a test uses to show it. */
    public static final String ID = "org.jawata.eclipse.chat.view";

    /** The mark kept with the view in the workbench's saved layout once it has been placed, which tells a restored chat from a new one. */
    static final String PLACED = "org.jawata.eclipse.chat.placed";

    private ChatPlacement chat;
    /** The workbench model entry of this view, held from the start because the services that find it may be gone when the view closes. */
    private MPart modelEntry;

    @Override
    public void createPartControl(Composite parent) {
        chat = new ChatPlacement(parent, () -> getSite().getShell(), message -> getViewSite().getActionBars().getStatusLineManager().setMessage(message));
        MPart part = getSite().getService(MPart.class);
        modelEntry = part;
        if (part != null && !part.getPersistedState().containsKey(PLACED)) {
            part.getPersistedState().put(PLACED, "yes");
            parent.getDisplay().asyncExec(this::placeWhereChosen);
        }
    }

    /** Puts a chat that has just opened where the person chose, asking once if they have not. */
    private void placeWhereChosen() {
        if (chat == null || chat.page().isDisposed()) {
            return;
        }
        ChatPlacementChoice.Where where = ChatPlacementChoice.resolve(getSite().getShell());
        IWorkbenchPage page = getSite().getPage();
        if (where == ChatPlacementChoice.Where.EDITOR_AREA && EditorAreaMove.moveInto(getSite())) {
            if (!page.isEditorAreaVisible()) {
                page.setEditorAreaVisible(true);
            }
            page.activate(this);
        } else if (where == ChatPlacementChoice.Where.SIDE_PANEL && EditorAreaMove.moveOut(getSite())) {
            page.activate(this);
        }
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
        forgetPlacement();
        super.dispose();
    }

    /** A chat the person closes is new when it opens again; one closed because the workbench ends is the same chat when it comes back. */
    private void forgetPlacement() {
        if (modelEntry != null && PlatformUI.isWorkbenchRunning() && !PlatformUI.getWorkbench().isClosing()) {
            modelEntry.getPersistedState().remove(PLACED);
        }
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
