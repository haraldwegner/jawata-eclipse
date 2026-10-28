package org.jawata.eclipse.host;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.McpEndpoint;

/**
 * Where the engine inside Eclipse listens (plan Stage 16, contract §4.2, his ruling A3-1): on the loopback address, on any free port,
 * and with no key. A process on the same machine reaches it; the transport refuses an endpoint without a key on any other address.
 *
 * <p>When the listener is up the engine reports its URL here, and whoever announces the engine (the note writer, Stage 16.5) is
 * told through {@link #onListening}.</p>
 */
public final class EclipseEndpointPolicy implements EndpointPolicy {

    private final AtomicReference<McpEndpoint> reached = new AtomicReference<>();
    private final AtomicReference<Consumer<McpEndpoint>> announcer = new AtomicReference<>(at -> { });

    @Override
    public String bindAddress() {
        return "127.0.0.1";
    }

    @Override
    public int port() {
        return 0;
    }

    @Override
    public Optional<String> key() {
        return Optional.empty();
    }

    @Override
    public void listening(McpEndpoint at) {
        reached.set(at);
        announcer.get().accept(at);
    }

    /** @return where the engine can be reached, once its listener is up */
    public Optional<McpEndpoint> reached() {
        return Optional.ofNullable(reached.get());
    }

    /**
     * Says who to tell when the listener comes up; if it already is, tells them at once.
     *
     * @param who the one announcing the engine's endpoint
     */
    public void onListening(Consumer<McpEndpoint> who) {
        announcer.set(who);
        McpEndpoint now = reached.get();
        if (now != null) {
            who.accept(now);
        }
    }
}
