package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jawata.eclipse.commit.CommitDraftCheck;
import org.jawata.eclipse.commit.CommitDraftView;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 22b, the live half of P22b (spec D13): a real agent is given the request the commit draft view's "Ask the agent" button
 * sends, in a repository with a change not yet committed, and must leave a draft the view accepts while the repository's HEAD does not move.
 *
 * <p>The button's own path (the view, the chat's command, the text sent) is pinned without an agent by {@code CommitDraftTest}; this class
 * covers what only a real agent can show, that it writes the draft where the view reads it. It costs tokens and needs the person's
 * {@code claude} login and the network, so every default build leaves it out; {@code scripts/28k-live-turn.sh} runs it by name when
 * {@code JAWATA_LIVE_TEST=LiveAskTest}.
 */
public class LiveAskTest {

    /** The agent adapter the plan names, at the version the plan names. */
    private static final String AGENT = "@agentclientprotocol/claude-agent-acp@0.93.0";

    /** How long the one prompt may take: ten minutes, or JAWATA_LIVE_LIMIT_SECONDS. */
    private static final long TURN_LIMIT_MILLIS = Long.parseLong(System.getenv().getOrDefault("JAWATA_LIVE_LIMIT_SECONDS", "600")) * 1000L;

    @Test
    public void aRealAgentAnswersTheAskButtonsRequestWithADraftAndHeadDoesNotMove() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "the git commands below are for Linux and macOS");
        String npx = System.getenv("JAWATA_LIVE_NPX");
        assertNotNull(npx, "JAWATA_LIVE_NPX names npx: run scripts/28k-live-turn.sh, which sets it");
        Path repo = Files.createTempDirectory("jawata-live-ask");
        try {
            git(repo, "init", "-q");
            git(repo, "config", "user.name", "Fixture");
            git(repo, "config", "user.email", "fixture@example.invalid");
            Files.writeString(repo.resolve("greeting.txt"), "hello\n");
            git(repo, "add", "greeting.txt");
            git(repo, "commit", "-q", "-m", "first");
            Files.writeString(repo.resolve("greeting.txt"), "hello, world\n");
            String before = git(repo, "rev-parse", "HEAD") + " " + git(repo, "rev-list", "--count", "HEAD");
            Path draft = repo.resolve(".git").resolve("jawata-commit-draft.txt");

            Map<String, String> environment = new HashMap<>();
            String model = System.getenv("JAWATA_LIVE_MODEL");
            if (model != null && !model.isBlank()) {
                environment.put("ANTHROPIC_MODEL", model);
            }
            String configFolder = System.getenv("JAWATA_LIVE_CONFIG_DIR");
            if (configFolder != null && !configFolder.isBlank()) {
                // a folder holding only the login: the agent then sees none of the person's own servers, hooks or settings
                environment.put("CLAUDE_CONFIG_DIR", configFolder);
            }
            String request = CommitDraftView.request(repo.resolve(".git"));
            FixedChatSettings settings = new FixedChatSettings(List.of(npx, "-y", AGENT), environment, repo);
            try (HostTestPage page = new HostTestPage(settings, new RecordingDialogs(), () -> Optional.empty())) {
                page.run("window.playLive({\"turns\": 1, \"timeoutMs\": " + TURN_LIMIT_MILLIS + "});");
                page.command("connectAgent", Map.of());
                page.command("sendPrompt", Map.of("text", request));
                JsonNode played = waitFor(page, "played");
                List<String> seen = new ArrayList<>();
                for (JsonNode tool : played.path("tools")) {
                    seen.add(tool.path("title").asText() + " [" + tool.path("status").asText() + "]");
                }
                System.out.println("jawata-live-ask request: " + request);
                System.out.println("jawata-live-ask tool calls: " + seen);
                System.out.println("jawata-live-ask prompts ended: " + played.path("ended").asInt() + " of 1");
                assertTrue(Files.isRegularFile(draft), "the agent wrote the draft where the view reads it (" + draft + "); the calls: " + seen);
                String text = Files.readString(draft);
                System.out.println("jawata-live-ask draft: " + text);
                assertTrue(!text.isBlank(), "the draft has text");
                assertTrue(CommitDraftCheck.fromBundle().refusal(text).isEmpty(), "the view accepts the draft: " + CommitDraftCheck.fromBundle().refusal(text));
                assertEquals(before, git(repo, "rev-parse", "HEAD") + " " + git(repo, "rev-list", "--count", "HEAD"), "nothing was committed");
                assertEquals("hello, world", Files.readString(repo.resolve("greeting.txt")).strip(), "the person's file is as it was");
            }
        } finally {
            try (Stream<Path> walk = Files.walk(repo)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out.strip();
    }

    /** The page's call with this op, waited for as long as a real turn takes. */
    private static JsonNode waitFor(HostTestPage page, String op) throws InterruptedException {
        long until = System.currentTimeMillis() + TURN_LIMIT_MILLIS + 60_000L;
        while (System.currentTimeMillis() < until) {
            Optional<JsonNode> call = page.callWith(op);
            if (call.isPresent()) {
                return call.get();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the page never made its \"" + op + "\" call; calls so far: " + page.calls().size());
    }
}
