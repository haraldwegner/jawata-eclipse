package org.jawata.eclipse.chat;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything the chat asks of the person, and every notice it gives (plan Stage 21, step 21.9; ledger 28i-C13-10). The chat's core
 * runs in the page and cannot show a dialog, so each of these crosses the bridge; a question is answered LATER through the callback,
 * never inside the page's call, because a modal dialog opened inside a page callback would hold the page's script while the dialog
 * ran. Production shows Eclipse's own dialogs ({@link WorkbenchDialogs}); a test records.
 */
public interface ChatDialogs {

    /** One way to sign in that the agent offers. */
    record Method(String id, String name, String description) {
    }

    /**
     * Asks a question that is answered with one action or by cancelling.
     *
     * @param question the question
     * @param yes the label of the action
     * @param answer told true when the person took the action, false when they cancelled; called on the user interface thread
     */
    void confirm(String question, String yes, Consumer<Boolean> answer);

    /**
     * Asks the person to pick one of the agent's ways to sign in.
     *
     * @param agentName the agent that offers them
     * @param methods the ways, at least one
     * @param answer told the id of the one picked, or empty when the person cancelled
     */
    void choose(String agentName, List<Method> methods, Consumer<Optional<String>> answer);

    /**
     * Opens the place where the agent is set up.
     *
     * @param done called when the person has closed it
     */
    void configureAgent(Runnable done);

    /**
     * Shows that work is under way, in the way Eclipse shows background work.
     *
     * @param ticket names this work for {@link #progressEnded}
     * @param title what the work is
     */
    void progressBegun(String ticket, String title);

    /**
     * Takes the work shown for the ticket away.
     *
     * @param ticket the ticket given to {@link #progressBegun}
     */
    void progressEnded(String ticket);

    /**
     * Tells the person something that needs no answer.
     *
     * @param message the notice
     */
    void info(String message);

    /**
     * Tells the person that something went wrong.
     *
     * @param message what went wrong
     */
    void error(String message);
}
