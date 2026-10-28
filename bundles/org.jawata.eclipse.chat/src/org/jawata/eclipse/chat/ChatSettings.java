package org.jawata.eclipse.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What the chat reads from the person's setup (plan Stage 21): which agent to start, where it works, and what the chat calls itself.
 * Read once, before the chat's core starts, because the core asks for these at once and a page cannot wait on Java for them
 * (ledger 28i-C11-5); a later change is pushed to the page.
 */
public interface ChatSettings {

    /**
     * The agent's program and arguments.
     *
     * @return the command line, empty when the person has set no agent
     */
    List<String> agentCommand();

    /**
     * Variables added to the agent's environment.
     *
     * @return the variables, possibly none
     */
    Map<String, String> agentEnvironment();

    /**
     * The folder the agent works in.
     *
     * @return the folder
     */
    Path workingFolder();

    /**
     * Where the person sets the agent up, as a noun phrase the chat puts in its messages ("the jawata chat preferences").
     *
     * @return the phrase
     */
    String agentSetup();

    /**
     * The version the chat tells the agent it is.
     *
     * @return the plug-in's version
     */
    String clientVersion();
}
