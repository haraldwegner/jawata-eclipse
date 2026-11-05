package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jawata.eclipse.access.EngineAddress;
import org.jawata.eclipse.chat.PreferencesChatSettings;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Plan Stage 25a, step 25a.4 (Sprint 28h D1, D42): the recorded sessions of the contract play against jawata's own agent in the Eclipse
 * chat, with no start step and no connection step of the person's own. The person has set no agent: the chat starts the engine's bridge
 * ({@code PreferencesChatSettings} falls back to {@code EngineAddress.agentCommand()}), which reaches the agent face on the listener of the
 * engine inside this Eclipse. The agent's brain is the setting {@code jawata.agent.brain}; here it is the replay agent of the contract
 * package playing the recording, so what the brain says is the recorded text and nothing costs a token or needs a login. A recording is
 * a brain's side: the chat's side is the page, the relay, the bridge and the agent, all real.
 */
public class JawataAgentInChatTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BRAIN = "jawata.agent.brain";
    private static final String SEATS = "jawata.agent.seatsDir";
    private static final long TURN_MILLIS = 45_000L;

    private IEclipsePreferences engineSettings;
    private IEclipsePreferences chatSettings;
    private String agentBefore;
    private Path seats;

    @BeforeEach
    public void aChatWithNoAgentOfItsOwn() {
        engineSettings = InstanceScope.INSTANCE.getNode(EclipseEngine.PLUGIN_ID);
        chatSettings = InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);
        agentBefore = chatSettings.get(PreferencesChatSettings.AGENT, null);
        chatSettings.remove(PreferencesChatSettings.AGENT);
    }

    @AfterEach
    public void putTheSettingsBack() throws IOException {
        engineSettings.remove(BRAIN);
        engineSettings.remove(SEATS);
        if (agentBefore != null) {
            chatSettings.put(PreferencesChatSettings.AGENT, agentBefore);
        }
        if (seats != null) {
            try (var files = Files.list(seats)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(seats);
        }
    }

    /** The brain is the replay agent on this recording of the contract package. */
    private void brainPlays(String session) throws Exception {
        engineSettings.put(BRAIN, JSON.writeValueAsString(Map.of("command", ChatCheckout.replayCommand(session))));
    }

    /** What the page told its view while one prompt was sent with no connection command first. */
    private JsonNode oneTurn(String prompt) throws Exception {
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        // the settings are the real ones: no agent is set, so the agent the chat starts is the one the engine gives
        try (HostTestPage page = new HostTestPage(new PreferencesChatSettings(), new RecordingDialogs(), EngineAddress::url)) {
            page.run("window.playLive({\"turns\": 1, \"timeoutMs\": " + TURN_MILLIS + "});");
            // the chat's ordinary first step, the same for every agent: jawata's has none of its own
            page.command("connectAgent", Map.of());
            page.command("sendPrompt", Map.of("text", prompt));
            return waitFor(page, "played").path("body");
        }
    }

    private static JsonNode waitFor(HostTestPage page, String op) throws InterruptedException {
        long until = System.currentTimeMillis() + TURN_MILLIS + 30_000L;
        while (System.currentTimeMillis() < until) {
            Optional<JsonNode> call = page.callWith(op);
            if (call.isPresent()) {
                return call.get();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the page never made its \"" + op + "\" call; calls so far: " + page.calls().size());
    }

    private static List<String> updates(JsonNode body, String kind) {
        List<String> found = new ArrayList<>();
        body.path("messages").forEach(message -> {
            JsonNode update = message.path("update");
            if ("sessionUpdate".equals(message.path("type").asText()) && kind.equals(update.path("sessionUpdate").asText())) {
                found.add(update.path("content").path("text").asText());
            }
        });
        return found;
    }

    private static List<String> types(JsonNode body) {
        List<String> types = new ArrayList<>();
        body.path("messages").forEach(message -> types.add(message.path("type").asText()));
        return types;
    }

    @Test
    public void r1APromptIsAnsweredByJawatasAgentWithTheRecordedTextAndNoStartStep() throws Exception {
        brainPlays("R1");
        JsonNode body = oneTurn("Reply with exactly the single word pong. Do not use any tools.");
        System.out.println("jawata-agent-in-chat R1 stream: " + body);
        assertEquals(List.of("pong"), updates(body, "agent_message_chunk"), "the recorded answer reaches the view; the stream: " + types(body));
        List<String> types = types(body);
        assertTrue(types.contains("promptStart") && types.indexOf("promptEnd") > types.indexOf("promptStart"), "the turn starts and ends: " + types);
        assertEquals("end_turn", endOf(body).path("stopReason").asText(), "the turn ended as a turn ends");
        assertEquals("jawata", agentName(body), "the agent the chat reached is jawata's own, not the recorded Claude");
    }

    @Test
    public void r9TheBrainsThoughtReachesTheViewBeforeItsAnswer() throws Exception {
        brainPlays("R9");
        JsonNode body = oneTurn("Think about it, then answer.");
        System.out.println("jawata-agent-in-chat R9 stream: " + body);
        assertTrue(!updates(body, "agent_thought_chunk").isEmpty(), "the thought of the recording reaches the view; the stream: " + types(body));
        assertTrue(!updates(body, "agent_message_chunk").isEmpty(), "and the answer follows");
    }

    /** One agent started the way the chat starts it, spoken to line by line the way the chat's relay speaks to it. */
    private static final class Wire implements AutoCloseable {
        private final Process process;
        private final List<JsonNode> seen = new CopyOnWriteArrayList<>();

        Wire(List<String> command) throws IOException {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Thread reading = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                        if (!line.isBlank()) {
                            seen.add(JSON.readTree(line));
                        }
                    }
                } catch (IOException ended) {
                    seen.add(JSON.createObjectNode().put("readerEnded", String.valueOf(ended.getMessage())));
                }
            }, "jawata agent test reader");
            reading.setDaemon(true);
            reading.start();
        }

        /** Sends one request and returns its answer, waiting for it as long as a turn may take. */
        JsonNode ask(ObjectNode request) throws Exception {
            process.getOutputStream().write((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            long until = System.currentTimeMillis() + TURN_MILLIS;
            while (System.currentTimeMillis() < until) {
                for (JsonNode message : seen) {
                    if (message.has("id") && message.get("id").equals(request.get("id")) && !message.has("method")) {
                        return message;
                    }
                }
                Thread.sleep(50);
            }
            throw new AssertionError("no answer to " + request + "; the agent said: " + seen);
        }

        List<JsonNode> said() {
            return seen;
        }

        @Override
        public void close() throws InterruptedException {
            process.destroy();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static List<JsonNode> recorded(String session, String direction) throws IOException {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(ChatCheckout.session(session), StandardCharsets.UTF_8)) {
            if (!line.isBlank() && direction.equals(JSON.readTree(line).path("dir").asText())) {
                lines.add(JSON.readTree(line).path("msg"));
            }
        }
        return lines;
    }

    /**
     * The host's side of a recording, played to the live agent: every request the recorded host sent, in order, with the recorded session
     * id and working folder replaced by the live ones. The answers are returned by the id the recording gave each request.
     */
    private static Map<String, JsonNode> playHostSide(Wire agent, String session, Path folder) throws Exception {
        Map<String, JsonNode> answers = new java.util.LinkedHashMap<>();
        String live = null;
        for (JsonNode sent : recorded(session, "send")) {
            if (!sent.has("method") || !sent.has("id")) {
                continue;
            }
            ObjectNode request = (ObjectNode) sent.deepCopy();
            ObjectNode params = (ObjectNode) request.get("params");
            if (params.has("sessionId") && live != null) {
                params.put("sessionId", live);
            }
            if (params.has("cwd")) {
                params.put("cwd", folder.toString());
            }
            if (params.has("mcpServers")) {
                params.putArray("mcpServers");
            }
            JsonNode answer = agent.ask(request);
            answers.put(sent.get("id").asText(), answer);
            if ("session/new".equals(sent.get("method").asText())) {
                live = answer.path("result").path("sessionId").asText();
            }
        }
        return answers;
    }

    private static JsonNode option(JsonNode answer, String id) {
        for (JsonNode option : answer.path("result").path("configOptions")) {
            if (id.equals(option.path("id").asText())) {
                return option;
            }
        }
        return null;
    }

    private Path seatsFolder(String... names) throws IOException {
        seats = Files.createTempDirectory("jawata-seats-");
        for (String name : names) {
            Files.writeString(seats.resolve(name + ".md"), "---\nname: " + name + "\npurpose: the " + name + " seat\ncommand: " + name + "\n---\nBe the " + name + ".\n");
        }
        engineSettings.put(SEATS, seats.toString());
        return seats;
    }

    @Test
    public void r11TheAutonomySwitchIsSetAndEchoedAsTheRecordingShowsIt() throws Exception {
        // R11 was recorded after the chat commit chat.ref names; a checkout that has not got it cannot play it, and says so
        Assumptions.assumeTrue(Files.isRegularFile(ChatCheckout.session("R11")), "the chat checkout has no R11 (chat.ref predates it): " + ChatCheckout.session("R11"));
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        Path folder = Files.createTempDirectory("jawata-r11-");
        try (Wire agent = new Wire(EngineAddress.agentCommand().orElseThrow(() -> new AssertionError("the chat has no agent to start")))) {
            Map<String, JsonNode> answers = playHostSide(agent, "R11", folder);
            List<JsonNode> recordedAnswers = recorded("R11", "recv");
            int checked = 0;
            for (JsonNode sent : recorded("R11", "send")) {
                if (!"session/set_config_option".equals(sent.path("method").asText())) {
                    continue;
                }
                String wanted = sent.path("params").path("value").asText();
                JsonNode recordedOption = null;
                for (JsonNode answer : recordedAnswers) {
                    if (answer.has("id") && answer.get("id").equals(sent.get("id")) && answer.has("result")) {
                        recordedOption = option(answer, "autonomy");
                    }
                }
                JsonNode live = option(answers.get(sent.get("id").asText()), "autonomy");
                assertEquals(wanted, recordedOption.path("currentValue").asText(), "the recording echoes the switch it was given");
                assertEquals(wanted, live.path("currentValue").asText(), "and so does jawata's agent: " + answers.get(sent.get("id").asText()));
                assertEquals(values(recordedOption), values(live), "the same two values are offered");
                checked++;
            }
            assertEquals(2, checked, "the recording sets the switch on and off");
        } finally {
            Files.deleteIfExists(folder);
        }
    }

    private static List<String> values(JsonNode option) {
        List<String> values = new ArrayList<>();
        option.path("options").forEach(value -> values.add(value.path("value").asText()));
        return values;
    }

    @Test
    public void r3TheAgentAdvertisesTheSeatsItServesAsCommandsOfTheSeatGroup() throws Exception {
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        seatsFolder("tester", "scribe");
        Path folder = Files.createTempDirectory("jawata-r3-");
        try (Wire agent = new Wire(EngineAddress.agentCommand().orElseThrow(() -> new AssertionError("the chat has no agent to start")))) {
            playHostSide(agent, "R3", folder);
            Set<String> groups = new TreeSet<>();
            Set<String> names = new TreeSet<>();
            for (JsonNode message : agent.said()) {
                for (JsonNode command : message.path("params").path("update").path("availableCommands")) {
                    groups.add(command.path("_meta").path("jawata/group").asText());
                    names.add(command.path("name").asText());
                }
            }
            Set<String> recordedGroups = new TreeSet<>();
            for (JsonNode message : recorded("R3", "recv")) {
                for (JsonNode command : message.path("params").path("update").path("availableCommands")) {
                    recordedGroups.add(command.path("_meta").path("jawata/group").asText());
                }
            }
            assertTrue(recordedGroups.contains("seat"), "the recording groups seats: " + recordedGroups);
            assertEquals(Set.of("seat"), groups, "every command jawata advertises is a seat: " + names);
            assertEquals(Set.of("tester", "scribe"), names, "and they are the seat files of the folder the setting names");
        } finally {
            Files.deleteIfExists(folder);
        }
    }

    @Test
    public void r5EachSeatHasABrainAndModelOptionTheHostCanHandBackToTheSeatFile() throws Exception {
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        Path folder = Files.createTempDirectory("jawata-r5-");
        try (Wire agent = new Wire(EngineAddress.agentCommand().orElseThrow(() -> new AssertionError("the chat has no agent to start")))) {
            ObjectNode initialize = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 0).put("method", "initialize");
            initialize.putObject("params").put("protocolVersion", 1);
            agent.ask(initialize);
            ObjectNode created = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "session/new");
            created.putObject("params").put("cwd", folder.toString()).putArray("mcpServers");
            JsonNode opened = agent.ask(created);
            Set<String> seatOptions = new TreeSet<>();
            for (JsonNode option : opened.path("result").path("configOptions")) {
                if (option.path("id").asText().startsWith("seat-model:")) {
                    seatOptions.add(option.path("id").asText());
                }
            }
            Set<String> recordedSeatOptions = new TreeSet<>();
            for (JsonNode message : recorded("R5", "recv")) {
                for (JsonNode option : message.path("result").path("configOptions")) {
                    if (option.path("id").asText().startsWith("seat-model:")) {
                        recordedSeatOptions.add(option.path("id").asText());
                    }
                }
            }
            assertTrue(!recordedSeatOptions.isEmpty() && seatOptions.containsAll(recordedSeatOptions),
                "every seat the recording has an option for has one here: " + seatOptions + " / " + recordedSeatOptions);
            String configId = "seat-model:test-writer";
            ObjectNode set = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "session/set_config_option");
            set.putObject("params").put("sessionId", opened.path("result").path("sessionId").asText()).put("configId", configId).put("value", "seat-file");
            JsonNode echoed = agent.ask(set);
            assertEquals("seat-file", option(echoed, configId).path("currentValue").asText(), "the change is echoed with the options as they now stand: " + echoed);
        } finally {
            Files.deleteIfExists(folder);
        }
    }

    private static JsonNode endOf(JsonNode body) {
        for (JsonNode message : body.path("messages")) {
            if ("promptEnd".equals(message.path("type").asText())) {
                return message;
            }
        }
        throw new AssertionError("no promptEnd in " + types(body));
    }

    private static String agentName(JsonNode body) {
        for (JsonNode message : body.path("messages")) {
            String name = message.path("session").path("agentName").asText("");
            if (!name.isEmpty()) {
                return name;
            }
        }
        return "";
    }
}
