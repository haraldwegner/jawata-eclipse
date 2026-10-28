package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, steps 21.5 and 21.6: a recorded session, played by the replay agent of {@code jawata-chat} through the real Java relay,
 * the real adapter and chat-core's own controller in the Eclipse page, tells the view exactly what chat-core's golden for that session
 * says it tells it (normalised the way chat-core normalises: ids by first appearance, the machine's paths and the clock left out). The
 * same goldens are checked by chat-core on an in-memory host and by the VS Code extension on its real host, so a difference is this
 * host's. The two derived sessions (R9, R10) have no golden, as in the VS Code host's tests: they are held to their shape.
 */
public class ChatReplayTest {

    private final ObjectMapper json = new ObjectMapper();

    private List<JsonNode> recorded(String session) throws IOException {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(ChatCheckout.session(session), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                lines.add(json.readTree(line));
            }
        }
        return lines;
    }

    /** The choices the recorded person made to the agent's proposals, in order. */
    private List<String> recordedAnswers(String session) throws IOException {
        List<String> answers = new ArrayList<>();
        for (JsonNode line : recorded(session)) {
            JsonNode option = line.path("msg").path("result").path("outcome").path("optionId");
            if ("send".equals(line.path("dir").asText()) && option.isTextual()) {
                answers.add(option.asText());
            }
        }
        return answers;
    }

    /** The session id the recording opens with session/load. */
    private String loadedSessionId(String session) throws IOException {
        for (JsonNode line : recorded(session)) {
            if ("session/load".equals(line.path("msg").path("method").asText())) {
                return line.path("msg").path("params").path("sessionId").asText();
            }
        }
        throw new AssertionError(session + " does not load a session");
    }

    /** What the Eclipse page told its view while the given script played the session, in chat-core's golden form. */
    private JsonNode played(String session, String script) throws IOException {
        FixedChatSettings settings = new FixedChatSettings(ChatCheckout.replayCommand(session), Map.of(), FixedChatSettings.withoutAgent().workingFolder());
        try (HostTestPage page = new HostTestPage(settings)) {
            page.run(script);
            return page.waitForCall("played").path("body");
        }
    }

    private JsonNode promptPlayed(String session) throws IOException {
        return played(session, "window.playSession({\"answers\": " + json.writeValueAsString(recordedAnswers(session)) + "});");
    }

    private void assertEqualsGolden(String session, JsonNode actual) throws IOException {
        JsonNode golden = json.readTree(ChatCheckout.golden(session).toFile());
        assertTrue(actual.path("messages").size() > 0, "the page was told something");
        if (!golden.equals(actual)) {
            JsonNode expectedMessages = golden.path("messages");
            JsonNode actualMessages = actual.path("messages");
            for (int i = 0; i < Math.max(expectedMessages.size(), actualMessages.size()); i++) {
                assertEquals(expectedMessages.get(i), actualMessages.get(i), "message " + i + " of the view stream for " + session);
            }
            assertEquals(golden, actual, "the view stream for " + session + " (the messages match, so a rule differs)");
        }
    }

    @Test
    public void r1APromptAndAStreamedAnswerTellTheViewWhatChatCoreSays() throws Exception {
        assertEqualsGolden("R1", promptPlayed("R1"));
    }

    @Test
    public void r2AToolCallAndItsOutcomeTellTheViewWhatChatCoreSays() throws Exception {
        assertEqualsGolden("R2", promptPlayed("R2"));
    }

    @Test
    public void r3TheGroupedCommandListTellsTheViewWhatChatCoreSays() throws Exception {
        assertEqualsGolden("R3", promptPlayed("R3"));
    }

    @Test
    public void r7TwoProposalsOneAllowedAndOneFailedTellTheViewWhatChatCoreSays() throws Exception {
        assertEqualsGolden("R7", promptPlayed("R7"));
    }

    @Test
    public void r6ALoadedSessionIsReplayedBetweenItsLoadStartAndItsLoadEnd() throws Exception {
        JsonNode actual = played("R6", "window.loadSession({\"sessionId\": " + json.writeValueAsString(loadedSessionId("R6")) + "});");
        List<String> types = new ArrayList<>();
        actual.path("messages").forEach(message -> types.add(message.path("type").asText()));
        assertTrue(types.indexOf("loadSessionStart") != -1 && types.indexOf("loadSessionEnd") > types.indexOf("loadSessionStart"), "load start and end: " + types);
        assertEqualsGolden("R6", actual);
    }

    /** A derived session has no golden: every kind of update the agent sent reaches the view, and the turn ends. */
    private void assertShapeReachesTheView(String session) throws IOException {
        Set<String> sent = new LinkedHashSet<>();
        for (JsonNode line : recorded(session)) {
            JsonNode kind = line.path("msg").path("params").path("update").path("sessionUpdate");
            if ("recv".equals(line.path("dir").asText()) && kind.isTextual()) {
                sent.add(kind.asText());
            }
        }
        assertTrue(!sent.isEmpty(), session + " holds updates the agent sent");
        JsonNode messages = promptPlayed(session).path("messages");
        Set<String> told = new LinkedHashSet<>();
        messages.forEach(message -> {
            JsonNode kind = message.path("update").path("sessionUpdate");
            if ("sessionUpdate".equals(message.path("type").asText()) && kind.isTextual()) {
                told.add(kind.asText());
            }
        });
        assertTrue(told.containsAll(sent), "every kind of update the agent sent reaches the view: sent " + sent + ", told " + told);
        List<String> types = new ArrayList<>();
        messages.forEach(message -> types.add(message.path("type").asText()));
        assertTrue(types.contains("promptStart") && types.indexOf("promptEnd") > types.indexOf("promptStart"), "and the turn starts and ends: " + types);
    }

    @Test
    public void r9AThoughtBeforeTheAnswerReachesTheView() throws Exception {
        assertShapeReachesTheView("R9");
    }

    @Test
    public void r4TwoOverlappingHiresReachTheView() throws Exception {
        assertShapeReachesTheView("R4");
    }

    @Test
    public void r10APlanUpdateDuringTheTurnReachesTheView() throws Exception {
        assertShapeReachesTheView("R10");
    }
}
