package org.jawata.eclipse.tests;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.eclipse.chat.ChatSettings;

/** A chat setup given in the test: the agent to start (none when the command is empty) and the folder it works in. */
record FixedChatSettings(List<String> agentCommand, Map<String, String> agentEnvironment, Path workingFolder) implements ChatSettings {

    /** No agent set, working in the temporary folder. */
    static FixedChatSettings withoutAgent() {
        return new FixedChatSettings(List.of(), Map.of(), Path.of(System.getProperty("java.io.tmpdir")));
    }

    @Override
    public String agentSetup() {
        return "the test settings";
    }

    @Override
    public String clientVersion() {
        return "0.0.0-test";
    }
}
