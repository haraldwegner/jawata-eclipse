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
    private final ChatDialogs dialogs;
    private final Runnable stopWatching;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param page the page this host serves; its handler is set here
     * @param settings what the chat reads from the person's setup
     * @param log where the host and the agents' standard error write
     * @param engineUrl where the engine in this Eclipse can be reached now, empty when it cannot yet; the agent is offered it as an MCP server
     * @param dialogs how the person is asked and told things: the chat's questions, work in progress and notices
     */
    public ChatHost(ChatPage page, ChatSettings settings, Consumer<String> log, Supplier<Optional<String>> engineUrl, ChatDialogs dialogs) {
        this.page = page;
        this.settings = settings;
        this.log = log;
        this.relay = new AgentRelay(this, log);
        this.offer = new McpOffer(engineUrl);
        this.dialogs = dialogs;
        this.stopWatching = settings.onChange(this::settingsChanged);
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
                case "info" -> {
                    log.accept("[info] " + op.path("message").asText());
                    dialogs.info(op.path("message").asText());
                    yield null;
                }
                case "error" -> {
                    log.accept("[error] " + op.path("message").asText());
                    dialogs.error(op.path("message").asText());
                    yield null;
                }
                case "progress" -> {
                    String ticket = op.path("ticket").asText();
                    if ("begin".equals(op.path("phase").asText())) {
                        dialogs.progressBegun(ticket, op.path("title").asText());
                    } else {
                        dialogs.progressEnded(ticket);
                    }
                    yield null;
                }
                case "confirm" -> {
                    String ticket = op.path("ticket").asText();
                    dialogs.confirm(op.path("question").asText(), op.path("yes").asText(), taken -> answer(ticket, taken));
                    yield null;
                }
                case "confirmAuthMethod" -> {
                    String ticket = op.path("ticket").asText();
                    String text = op.path("agentName").asText() + " signs you in with " + op.path("method").path("name").asText() + ".";
                    dialogs.confirm(text, "Continue", taken -> answer(ticket, taken));
                    yield null;
                }
                case "chooseAuthMethod" -> {
                    String ticket = op.path("ticket").asText();
                    dialogs.choose(op.path("agentName").asText(), methodsOf(op.path("methods")), picked -> answer(ticket, picked.orElse(null)));
                    yield null;
                }
                case "configureAgent" -> {
                    String ticket = op.path("ticket").asText();
                    dialogs.configureAgent(() -> answer(ticket, null));
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

    /** The settings the page reads at once: the agent, the folder it works in, and where the person sets them. */
    private Map<String, Object> configMap() {
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
        return config;
    }

    private String config() throws JsonProcessingException {
        Map<String, Object> config = configMap();
        config.put("clientInfo", Map.of("name", "eclipse-jawata-chat", "version", settings.clientVersion()));
        return json.writeValueAsString(config);
    }

    /** The person changed the setup: an open page follows it, so its next question or agent start uses the new values. */
    private void settingsChanged() {
        Map<String, Object> event = configMap();
        event.put("kind", "settings");
        deliver(event);
    }

    /** Answers a question the page asked, by the ticket it gave. */
    private void answer(String ticket, Object value) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", "answer");
        event.put("ticket", ticket);
        event.put("value", value);
        deliver(event);
    }

    private static List<ChatDialogs.Method> methodsOf(JsonNode methods) {
        List<ChatDialogs.Method> found = new ArrayList<>();
        methods.forEach(method -> found.add(new ChatDialogs.Method(method.path("id").asText(), method.path("name").asText(), method.path("description").asText(""))));
        return found;
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
        stopWatching.run();
        relay.close();
    }
}
