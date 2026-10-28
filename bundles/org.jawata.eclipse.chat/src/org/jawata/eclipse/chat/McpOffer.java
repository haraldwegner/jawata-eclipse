package org.jawata.eclipse.chat;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Offers the agent jawata's tools (plan Stage 21, step 21.12; spec D3): the chat's core asks the agent for a session with no MCP servers,
 * and this adds the engine inside this Eclipse to the three requests that name them, {@code session/new}, {@code session/load} and
 * {@code session/resume}, as an HTTP server called {@value #NAME} at the engine's in-memory address, with no key (the endpoint inside
 * Eclipse has none, contract §4.2). Every other message passes unchanged. When the engine has no address yet the request passes
 * unchanged too: the offer is made with the next session, once the endpoint is reached.
 */
public final class McpOffer {

    /** The name the agent knows the engine by. */
    public static final String NAME = "jawata";

    private static final Set<String> SESSION_REQUESTS = Set.of("session/new", "session/load", "session/resume");

    private final Supplier<Optional<String>> engineUrl;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param engineUrl where the engine can be reached now, empty when it cannot yet
     */
    public McpOffer(Supplier<Optional<String>> engineUrl) {
        this.engineUrl = engineUrl;
    }

    /**
     * The message to send the agent.
     *
     * @param message one message from the chat, as the JSON text of one line
     * @return the same message, or a session request with the engine added to its MCP servers
     */
    public String apply(String message) {
        if (!message.contains("\"session/")) {
            return message;
        }
        try {
            JsonNode read = json.readTree(message);
            if (!SESSION_REQUESTS.contains(read.path("method").asText()) || !read.path("params").isObject()) {
                return message;
            }
            Optional<String> url = engineUrl.get();
            if (url.isEmpty()) {
                return message;
            }
            ObjectNode params = (ObjectNode) read.get("params");
            ArrayNode servers = params.withArray("mcpServers");
            for (JsonNode server : servers) {
                if (NAME.equals(server.path("name").asText())) {
                    return message;
                }
            }
            servers.addObject().put("type", "http").put("name", NAME).put("url", url.get()).putArray("headers");
            return json.writeValueAsString(read);
        } catch (IOException unreadable) {
            return message;
        }
    }
}
