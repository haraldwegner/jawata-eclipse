package org.jawata.eclipse.chat;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

/**
 * What the editor area is asked to open for the chat (plan Stage 23): no file, only the chat. Every instance equals every other, so
 * opening the chat twice shows the one page instead of a second conversation.
 */
public final class ChatEditorInput implements IEditorInput {

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public ImageDescriptor getImageDescriptor() {
        return null;
    }

    @Override
    public String getName() {
        return "jawata chat";
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }

    @Override
    public String getToolTipText() {
        return "The jawata chat, in the editor area";
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChatEditorInput;
    }

    @Override
    public int hashCode() {
        return ChatEditorInput.class.hashCode();
    }
}
