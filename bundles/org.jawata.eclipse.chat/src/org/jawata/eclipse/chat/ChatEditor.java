package org.jawata.eclipse.chat;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

/**
 * The chat as a large page in the editor area (plan Stage 23, spec D10): the person opens it from the command "Open jawata chat in the
 * editor area", and it shows the chat built by {@link ChatPlacement}, the same one the view shows. It is never dirty and cannot be
 * saved, because the chat's conversation is the agent's and not a file's.
 */
public final class ChatEditor extends EditorPart {

    /** The id plugin.xml gives the editor, which a command or a test uses to open it. */
    public static final String ID = "org.jawata.eclipse.chat.editor";

    private ChatPlacement chat;

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        setSite(site);
        setInput(input);
        setPartName(input.getName());
    }

    @Override
    public void createPartControl(Composite parent) {
        chat = new ChatPlacement(parent, () -> getSite().getShell(), message -> getEditorSite().getActionBars().getStatusLineManager().setMessage(message));
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

    @Override
    public void doSave(IProgressMonitor monitor) {
        // nothing to save: the chat is not a document
    }

    @Override
    public void doSaveAs() {
        // nothing to save: the chat is not a document
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    /**
     * The page this editor shows.
     *
     * @return the page, or null before the editor's controls exist
     */
    public ChatPage page() {
        return chat == null ? null : chat.page();
    }

    /**
     * The host serving this editor's page, through which an Eclipse command sends the chat a command.
     *
     * @return the host, or null before the editor's controls exist
     */
    public ChatHost host() {
        return chat == null ? null : chat.host();
    }
}
