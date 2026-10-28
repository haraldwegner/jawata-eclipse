package org.jawata.eclipse.tests;

import java.util.Optional;

import org.jawata.eclipse.chat.ChatPlacementChoice;
import org.jawata.eclipse.chat.ChatPlacementChoice.Where;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fixes where a newly opened chat goes for the length of a test, so no test waits for the question a first-time chat asks the person, and
 * puts the setting back afterwards. Used as an extension it chooses the side panel, where Eclipse opens a view; a test that cares about the
 * place uses {@link #choose(Where)} itself.
 */
public final class ChatPlacementSetup implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ChatPlacementSetup.class);

    /**
     * Chooses a place and gives what puts the person's setting back.
     *
     * @param where the place a newly opened chat goes
     * @return puts back what was set before, including nothing set
     */
    static Runnable choose(Where where) {
        Optional<Where> before = ChatPlacementChoice.chosen();
        ChatPlacementChoice.choose(where);
        return () -> before.ifPresentOrElse(ChatPlacementChoice::choose, ChatPlacementChoice::askAgain);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put(context.getUniqueId(), choose(Where.SIDE_PANEL));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Runnable putBack = context.getStore(NAMESPACE).remove(context.getUniqueId(), Runnable.class);
        if (putBack != null) {
            putBack.run();
        }
    }
}
