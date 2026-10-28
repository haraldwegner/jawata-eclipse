package org.jawata.eclipse.chat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Java end of the chat page's host bridge (plan Stage 21, step 21.4). The core runs in the page and asks its host for things; the
 * adapter in the page ({@code scripts/chat-page/host-adapter.js}) answers what it can and sends the rest here as one JSON object with an
 * {@code op}. This class answers {@code config} at once, starts, writes to and ends the agent through the {@link AgentRelay}, and tells the
 * page what the agents do. It reads the ops it acts on and relays the agents' messages without reading them.
 *
 * <p>
 * The choices that need the person (the chooser, the progress, the notices, the settings page) are answered in steps 21.8 and 21.9; until
 * then a request that needs the person is answered as cancelled and written to the log, so the core is never left waiting.
 */
public final class ChatHost implements AgentRelay.Listener, AutoCloseable {

    /** The key the core stores the one agent under. */
    static final String AGENT_KEY = "agent";

    private final ChatPage page;
    private final ChatSettings settings;
    private final Consumer<String> log;
    private final AgentRelay relay;
    private final McpOffer offer;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param page the page this host serves; its handler is set here
     * @param settings what the chat reads from the person's setup
     * @param log where the host and the agents' standard error write
     * @param engineUrl where the engine in this Eclipse can be reached now, empty when it cannot yet; the agent is offered it as an MCP server
     */
    public ChatHost(ChatPage page, ChatSettings settings, Consumer<String> log, Supplier<Optional<String>> engineUrl) {
        this.page = page;
        this.settings = settings;
        this.log = log;
        this.relay = new AgentRelay(this, log);
        this.offer = new McpOffer(engineUrl);
        page.setHandler(this::handle);
    }

    /**
     * Answers one call from the page.
     *
     * @param call one op as JSON text
     * @return the answer as JSON text, or null when the op has none
     */
    public String handle(String call) {
        try {
            JsonNode op = json.readTree(call);
            return switch (op.path("op").asText()) {
                case "config" -> config();
                case "started" -> {
                    log.accept("The chat page started on view contract " + op.path("contract").asInt(-1) + ".");
                    yield null;
                }
                case "spawn" -> {
                    spawn(op);
                    yield null;
                }
                case "write" -> {
                    relay.send(op.path("id").asText(), offer.apply(op.path("message").asText()));
                    yield null;
                }
                case "kill" -> String.valueOf(relay.kill(op.path("id").asText()));
                case "log" -> {
                    log.accept(op.path("message").asText());
                    yield null;
                }
                case "info", "error" -> {
                    log.accept("[" + op.path("op").asText() + "] " + op.path("message").asText());
                    yield null;
                }
                case "progress" -> null;
                case "confirm", "chooseAuthMethod", "confirmAuthMethod", "configureAgent" -> {
                    log.accept("The chat asked for \"" + op.path("op").asText() + "\", which is answered as cancelled until step 21.9.");
                    deliver(Map.of("kind", "answer", "ticket", op.path("ticket").asText()));
                    yield null;
                }
                default -> {
                    log.accept("The chat page sent a call this host does not know: " + call);
                    yield null;
                }
            };
        } catch (IOException unreadable) {
            log.accept("The chat page sent a call this host cannot read: " + unreadable.getMessage());
            return null;
        }
    }

    private String config() throws JsonProcessingException {
        Map<String, Object> agents = new LinkedHashMap<>();
        List<String> command = settings.agentCommand();
        if (!command.isEmpty()) {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("command", command.get(0));
            agent.put("args", command.subList(1, command.size()));
            agent.put("env", settings.agentEnvironment());
            agents.put(AGENT_KEY, agent);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("agents", agents);
        config.put("cwd", settings.workingFolder().toString());
        config.put("agentSetup", settings.agentSetup());
        config.put("clientInfo", Map.of("name", "eclipse-jawata-chat", "version", settings.clientVersion()));
        return json.writeValueAsString(config);
    }

    private void spawn(JsonNode op) {
        String id = op.path("id").asText();
        List<String> command = new ArrayList<>();
        command.add(op.path("command").asText());
        op.path("args").forEach(argument -> command.add(argument.asText()));
        Map<String, String> environment = new LinkedHashMap<>();
        op.path("env").fields().forEachRemaining(entry -> environment.put(entry.getKey(), entry.getValue().asText()));
        String cwd = op.path("cwd").asText("");
        try {
            relay.start(id, command, environment, cwd.isEmpty() ? null : Path.of(cwd));
        } catch (IOException | RuntimeException failed) {
            // a program that cannot start is told as an error and an ending, as a shell tells a missing program
            onError(id, "The agent could not be started: " + failed.getMessage());
            onClosed(id, null);
        }
    }

    @Override
    public void onMessage(String agentId, String message) {
        deliver(Map.of("kind", "agentMessage", "id", agentId, "message", message));
    }

    @Override
    public void onClosed(String agentId, Integer code) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", "agentClosed");
        event.put("id", agentId);
        event.put("code", code);
        deliver(event);
    }

    @Override
    public void onError(String agentId, String message) {
        deliver(Map.of("kind", "agentError", "id", agentId, "error", message));
    }

    private void deliver(Map<String, ?> event) {
        try {
            page.deliver(json.writeValueAsString(event));
        } catch (JsonProcessingException unwritable) {
            log.accept("The chat host could not write an event for the page: " + unwritable.getMessage());
        }
    }

    /** Ends every agent. */
    @Override
    public void close() {
        relay.close();
    }
}
