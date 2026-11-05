package org.jawata.eclipse.access;

import java.util.Optional;

import org.jawata.eclipse.host.AgentBridge;
import org.jawata.eclipse.host.EngineStartup;

/**
 * Where the engine inside this Eclipse can be reached, as plain text: the one thing the chat bundle may take from the engine (plan Stage
 * 21, step 21.12; contract §2.1). The chat names no engine or host type, so what crosses is the address of the endpoint, nothing else.
 */
public final class EngineAddress {

    private EngineAddress() {
    }

    /**
     * The MCP address of the engine in this Eclipse.
     *
     * @return the address, or empty while the engine has not started or its listener is not up yet
     */
    public static Optional<String> url() {
        return EngineStartup.running().flatMap(engine -> engine.endpoint().reached()).map(reached -> reached.url().toString());
    }

    /**
     * The command that starts jawata's own agent for the engine inside this Eclipse (plan Stage 25a.4): the engine's bridge program, which
     * the plug-in carries, pointed at the listener the engine runs. The chat uses it when the person has set no agent of their own, so
     * jawata's agent needs neither a start nor a connection step. Like {@link #url}, it names no engine or host type: it is a command line.
     *
     * @return the program and its arguments, or empty while the engine's listener is not up or when this build carries no bridge
     */
    public static Optional<java.util.List<String>> agentCommand() {
        try {
            Optional<java.net.URI> listener = EngineStartup.running().flatMap(engine -> engine.endpoint().reached()).map(reached -> reached.url());
            return listener.isPresent() ? AgentBridge.forEngineAt(listener.get()) : Optional.empty();
        } catch (java.io.IOException unwritable) {
            return Optional.empty();
        }
    }
}
