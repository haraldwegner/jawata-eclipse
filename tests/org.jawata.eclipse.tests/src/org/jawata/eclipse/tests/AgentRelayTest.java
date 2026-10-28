package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jawata.eclipse.chat.AgentRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, step 21.3: the relay carries whole messages between the page and the ACP agent and says when the agent ends. The agent
 * here is the real replay agent of {@code jawata-chat} playing the recorded session R1, so the lines are the ones Claude's adapter
 * wrote, not a stand-in.
 */
public class AgentRelayTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> notes = new CopyOnWriteArrayList<>();
    private final List<Integer> exits = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private volatile boolean ended;
    private volatile Integer exitCode;

    private final AgentRelay relay = new AgentRelay(new AgentRelay.Listener() {
        @Override
        public void onMessage(String agentId, String message) {
            messages.add(message);
            events.add("message");
            if (messages.size() % 400 == 0) {
                pause(25); // a page busy drawing is slower than the program writing
            }
        }

        @Override
        public void onClosed(String agentId, Integer code) {
            exitCode = code;
            exits.add(code == null ? -1 : code);
            events.add("closed");
            ended = true;
        }

        @Override
        public void onError(String agentId, String message) {
            errors.add(message);
        }
    }, notes::add);

    @AfterEach
    public void endAgents() {
        relay.close();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The messages the recorded client sent, in order: what the page would send. */
    private List<String> recordedRequests() throws IOException {
        List<String> requests = new ArrayList<>();
        for (String line : Files.readAllLines(ChatCheckout.session("R1"), StandardCharsets.UTF_8)) {
            JsonNode recorded = json.readTree(line);
            if ("send".equals(recorded.path("dir").asText())) {
                requests.add(json.writeValueAsString(recorded.path("msg")));
            }
        }
        return requests;
    }

    private JsonNode answerTo(int id) throws Exception {
        String line = Await.found("the answer to request " + id, () -> {
            for (String message : messages) {
                try {
                    JsonNode read = json.readTree(message);
                    if (read.path("id").asInt(-1) == id && read.has("result")) {
                        return Optional.of(message);
                    }
                } catch (IOException unreadable) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        });
        return json.readTree(line);
    }

    @Test
    public void theRecordedAgentAnswersTheRecordedRequestsThroughTheRelay() throws Exception {
        String agent = relay.start("agent_1", ChatCheckout.replayCommand("R1"), Map.of(), null);
        List<String> requests = recordedRequests();

        assertTrue(relay.send(agent, requests.get(0)), "the first request, initialize, is written");
        JsonNode initialised = answerTo(0);
        assertEquals(1, initialised.path("result").path("protocolVersion").asInt(), "the agent speaks protocol 1: " + initialised);
        assertEquals("0.93.0", initialised.path("result").path("agentInfo").path("version").asText(), "it is the recorded adapter: " + initialised);

        assertTrue(relay.send(agent, requests.get(1)), "the second request, session/new, is written");
        JsonNode created = answerTo(1);
        assertFalse(created.path("result").path("sessionId").asText().isEmpty(), "the session id arrives: " + created);
        assertTrue(errors.isEmpty(), "nothing went wrong: " + errors);
    }

    @Test
    public void everyMessageIsHandedOnBeforeTheEndIsReportedAndStderrGoesToTheLog() throws Exception {
        String script = "for (let i = 1; i <= 2000; i++) { console.log(JSON.stringify({n: i})); } console.error('about to stop'); process.exitCode = 3;";
        relay.start("agent_1", List.of("node", "-e", script), Map.of(), null);

        Await.until(() -> ended);

        assertEquals(2000, messages.size(), "every line arrived before the end was reported");
        assertEquals("{\"n\":1}", messages.get(0), "whole lines");
        assertEquals("{\"n\":2000}", messages.get(1999), "in order");
        assertEquals("closed", events.get(events.size() - 1), "the end comes after the last message");
        assertEquals(1, events.stream().filter("closed"::equals).count(), "and is reported once");
        assertEquals(Integer.valueOf(3), exitCode, "the exit code is reported");
        assertTrue(notes.stream().anyMatch(note -> note.contains("about to stop")), "stderr reached the log: " + notes);
    }

    @Test
    public void aKilledAgentIsReportedEndedWithNoCodeAndTheEnvironmentAndFolderReachIt() throws Exception {
        Path folder = Files.createTempDirectory("agent-relay");
        try {
            String script = "console.log(JSON.stringify({cwd: process.cwd(), env: process.env.JAWATA_RELAY_TEST})); setInterval(() => {}, 1000);";
            String agent = relay.start("agent_1", List.of("node", "-e", script), Map.of("JAWATA_RELAY_TEST", "yes"), folder);

            String first = Await.found("the program's first line", () -> messages.stream().findFirst());
            JsonNode seen = json.readTree(first);
            assertEquals("yes", seen.path("env").asText(), "the environment is added: " + first);
            assertEquals(folder.toRealPath().toString(), new File(seen.path("cwd").asText()).toPath().toRealPath().toString(), "the folder is the working folder");

            assertTrue(relay.kill(agent), "a running agent can be ended");
            Await.until(() -> ended);

            assertEquals(List.of(-1), exits, "ended by kill, so no code, as the core expects of a signal");
            assertFalse(relay.kill(agent), "an ended agent cannot be ended again");
            assertFalse(relay.send(agent, "{}"), "nor written to");
        } finally {
            Files.deleteIfExists(folder);
        }
    }

    @Test
    public void aMessageWithALineBreakIsRefusedBecauseOneLineIsOneMessage() throws Exception {
        String agent = relay.start("agent_1", List.of("node", "-e", "setInterval(() => {}, 1000);"), Map.of(), null);

        assertFalse(relay.send(agent, "{\"a\":\n1}"), "refused");

        assertEquals(1, errors.size(), "and the core is told why: " + errors);
        assertTrue(errors.get(0).contains("line break"), errors.get(0));
    }

    @Test
    public void aProgramThatDoesNotExistFailsToStartAndTheCommandIsNeverEmpty() {
        assertThrows(IOException.class, () -> relay.start("agent_1", List.of("jawata-no-such-agent-program"), Map.of(), null));
        assertThrows(IOException.class, () -> relay.start("agent_2", List.of(), Map.of(), null));
    }

    @Test
    public void onWindowsABareNpxIsFoundAsACommandFileAndRunThroughTheInterpreter() throws Exception {
        Path folder = Files.createTempDirectory("agent-path");
        try {
            Files.writeString(folder.resolve("npx.cmd"), "@echo off\n");
            List<String> command = List.of("npx", "-y", "some-agent@1.0.0");

            List<String> windows = AgentRelay.executable(command, List.of(folder), true);
            assertEquals(List.of("cmd", "/c", folder.resolve("npx.cmd").toString(), "-y", "some-agent@1.0.0"), windows);

            assertEquals(command, AgentRelay.executable(command, List.of(folder), false), "elsewhere the command is left as configured");
            assertEquals(List.of("node.exe", "x"), AgentRelay.executable(List.of("node.exe", "x"), List.of(folder), true), "a name with an extension is left as configured");
            assertEquals(List.of("claude"), AgentRelay.executable(List.of("claude"), List.of(folder), true), "a program the path does not hold is left for the system to refuse");
        } finally {
            Files.deleteIfExists(folder.resolve("npx.cmd"));
            Files.deleteIfExists(folder);
        }
    }
}
