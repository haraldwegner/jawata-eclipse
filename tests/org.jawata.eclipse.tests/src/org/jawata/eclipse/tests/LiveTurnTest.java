package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.jawata.eclipse.chat.McpOffer;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 21, step 21.15 (spec D3, decision C): ONE real turn. A real agent is started by the chat in Eclipse, is offered the engine
 * inside Eclipse, and is asked three things that need jawata's tools; the test passes when the page's stream holds a tool call with a
 * jawata title that completed.
 *
 * <p>It costs tokens and needs the person's {@code claude} login and the network, so it is never in the default build: the build excludes
 * it, and {@code scripts/28k-live-turn.sh} runs it by name. The script gives it the absolute path of {@code npx} in
 * {@code JAWATA_LIVE_NPX}, and may give the agent a model in {@code JAWATA_LIVE_MODEL}.
 */
public class LiveTurnTest {

    /** The agent adapter the plan names, at the version the plan names. */
    private static final String AGENT = "@agentclientprotocol/claude-agent-acp@0.93.0";

    /** How long the three prompts may take: fifteen minutes, or JAWATA_LIVE_LIMIT_SECONDS (a dry run with a recorded agent need not wait that long). */
    private static final long TURN_LIMIT_MILLIS = Long.parseLong(System.getenv().getOrDefault("JAWATA_LIVE_LIMIT_SECONDS", "900")) * 1000L;

    private static final List<String> PROMPTS = List.of(
        "Call the jawata tool health_check and tell me in one line how many tools it reports.",
        "Use the jawata tool search_symbols to find the class Greeter and tell me in one line which package it is in.",
        "Use the jawata tool rename_symbol to rename the class Greeter to Welcomer, then say in one line which files changed.");

    @Test
    public void aRealAgentCallsJawataToolsThroughTheChatInEclipse() throws Exception {
        String npx = System.getenv("JAWATA_LIVE_NPX");
        assertNotNull(npx, "JAWATA_LIVE_NPX names npx: run scripts/28k-live-turn.sh, which sets it");
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        IProject project = WorkspaceProjects.java("jawata-live-");
        try {
            Path folder = project.getLocation().toFile().toPath();
            Map<String, String> environment = new java.util.HashMap<>();
            String model = System.getenv("JAWATA_LIVE_MODEL");
            if (model != null && !model.isBlank()) {
                environment.put("ANTHROPIC_MODEL", model);
            }
            String configFolder = System.getenv("JAWATA_LIVE_CONFIG_DIR");
            if (configFolder != null && !configFolder.isBlank()) {
                // a folder holding only the login: the agent then sees none of the person's own servers, hooks or settings
                environment.put("CLAUDE_CONFIG_DIR", configFolder);
            }
            FixedChatSettings settings = new FixedChatSettings(List.of(npx, "-y", AGENT), environment, folder);
            try (HostTestPage page = new HostTestPage(settings, new RecordingDialogs(), () -> Optional.of(String.valueOf(at.url())))) {
                page.run("window.playLive({\"turns\": " + PROMPTS.size() + ", \"timeoutMs\": " + TURN_LIMIT_MILLIS + "});");
                page.command("connectAgent", Map.of());
                for (String prompt : PROMPTS) {
                    page.command("sendPrompt", Map.of("text", prompt));
                }
                JsonNode played = waitFor(page, "played");
                List<String> seen = new ArrayList<>();
                boolean completedJawataCall = false;
                for (JsonNode tool : played.path("tools")) {
                    seen.add(tool.path("title").asText() + " [" + tool.path("status").asText() + "]");
                    // the server the chat offers is called jawata (McpOffer.NAME), so its tools are mcp__jawata__<tool>; a tool of the person's own
                    // jawata servers (mcp__jawata-javata-dev__ and the like) is not the engine inside this Eclipse (the first live run, 2026-10-29)
                    if (tool.path("title").asText().contains("mcp__" + McpOffer.NAME + "__") && "completed".equals(tool.path("status").asText())) {
                        completedJawataCall = true;
                    }
                }
                System.out.println("jawata-live-turn tool calls: " + seen);
                System.out.println("jawata-live-turn prompts ended: " + played.path("ended").asInt() + " of " + PROMPTS.size());
                assertTrue(completedJawataCall, "a completed tool call with a jawata title is in the page's stream; the calls: " + seen + "; prompts ended: "
                    + played.path("ended").asInt() + "; calls the page made: " + page.calls().size());
                assertEquals(PROMPTS.size(), played.path("ended").asInt(), "every prompt ended; the calls: " + seen);
            }
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
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
