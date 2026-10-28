package org.jawata.eclipse.tests;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jawata.eclipse.chat.ChatDialogs;

/** The chat's questions and notices as a test sees them: each is written down and answered the way the test set it up. */
final class RecordingDialogs implements ChatDialogs {

    /** What the chat asked or said, in order, one line each. */
    final List<String> asked = new CopyOnWriteArrayList<>();
    /** The answer to every question that is answered yes or no. */
    volatile boolean yes = true;

    @Override
    public void confirm(String question, String yesLabel, Consumer<Boolean> answer) {
        asked.add("confirm: " + question + " [" + yesLabel + "]");
        answer.accept(yes);
    }

    @Override
    public void choose(String agentName, List<Method> methods, Consumer<Optional<String>> answer) {
        asked.add("choose: " + agentName + " " + methods.stream().map(Method::name).toList());
        answer.accept(methods.isEmpty() ? Optional.empty() : Optional.of(methods.get(0).id()));
    }

    @Override
    public void configureAgent(Runnable done) {
        asked.add("configureAgent");
        done.run();
    }

    @Override
    public void progressBegun(String ticket, String title) {
        asked.add("progress begun: " + title);
    }

    @Override
    public void progressEnded(String ticket) {
        asked.add("progress ended");
    }

    @Override
    public void info(String message) {
        asked.add("info: " + message);
    }

    @Override
    public void error(String message) {
        asked.add("error: " + message);
    }
}
