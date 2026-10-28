package org.jawata.eclipse.chat;

import java.util.Optional;
import java.util.function.Function;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Where a newly opened chat goes (plan Stage 23, his pick of 2026-10-28): in the editor area, in the middle of the workbench, or in the
 * side panel where Eclipse puts a view. The person says so once, the first time the chat opens, and can change it on the chat's preference
 * page; a chat that is already open, restored after a restart or dragged somewhere by the person stays where it is.
 */
public final class ChatPlacementChoice {

    /** The two places a chat can open. */
    public enum Where {
        /** The editor area, in the middle. */
        EDITOR_AREA("editor"),
        /** The side panel, where Eclipse opens views. */
        SIDE_PANEL("side");

        private final String stored;

        Where(String stored) {
            this.stored = stored;
        }

        /** What is written in the preference. */
        String stored() {
            return stored;
        }
    }

    private static volatile Function<Shell, Optional<Where>> asker = ChatPlacementChoice::askWithDialog;

    private ChatPlacementChoice() {
    }

    /**
     * What the person has chosen.
     *
     * @return the place, or empty when the person has not chosen yet
     */
    public static Optional<Where> chosen() {
        String stored = node().get(PreferencesChatSettings.PLACEMENT, "");
        for (Where where : Where.values()) {
            if (where.stored().equals(stored)) {
                return Optional.of(where);
            }
        }
        return Optional.empty();
    }

    /**
     * Writes the person's choice.
     *
     * @param where the place a newly opened chat goes
     */
    public static void choose(Where where) {
        IEclipsePreferences node = node();
        node.put(PreferencesChatSettings.PLACEMENT, where.stored());
        flush(node);
    }

    /** Forgets the choice, so the chat asks again the next time it opens. */
    public static void askAgain() {
        IEclipsePreferences node = node();
        node.remove(PreferencesChatSettings.PLACEMENT);
        flush(node);
    }

    /**
     * Where a chat that is opening now goes: the person's choice, or, when there is none, what the person answers to the question, which is then kept.
     *
     * @param shell the window the question belongs to
     * @return the place; the editor area when the person closes the question without answering (the question is then asked again next time)
     */
    static Where resolve(Shell shell) {
        Optional<Where> known = chosen();
        if (known.isPresent()) {
            return known.get();
        }
        Optional<Where> answered = asker.apply(shell);
        answered.ifPresent(ChatPlacementChoice::choose);
        return answered.orElse(Where.EDITOR_AREA);
    }

    /**
     * Replaces the question with another way of answering it, for a test that must not wait for a person.
     *
     * @param replacement answers the question for the given window
     * @return what puts the question back
     */
    public static Runnable askWith(Function<Shell, Optional<Where>> replacement) {
        Function<Shell, Optional<Where>> before = asker;
        asker = replacement;
        return () -> asker = before;
    }

    private static Optional<Where> askWithDialog(Shell shell) {
        MessageDialog question = new MessageDialog(shell, "Where should the jawata chat open?", null,
            "The chat can open in the middle of the workbench, next to your files, or in a side panel like other views. "
                + "You can change this later on the jawata chat preference page.",
            MessageDialog.QUESTION, new String[] { "In the middle", "In a side panel" }, 0);
        return switch (question.open()) {
            case 0 -> Optional.of(Where.EDITOR_AREA);
            case 1 -> Optional.of(Where.SIDE_PANEL);
            default -> Optional.empty();
        };
    }

    private static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);
    }

    private static void flush(IEclipsePreferences node) {
        try {
            node.flush();
        } catch (BackingStoreException unsaved) {
            // the choice still holds in this session; the next start asks again
        }
    }
}
