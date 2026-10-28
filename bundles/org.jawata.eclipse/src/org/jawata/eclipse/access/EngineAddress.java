package org.jawata.eclipse.access;

import java.util.Optional;

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
}
