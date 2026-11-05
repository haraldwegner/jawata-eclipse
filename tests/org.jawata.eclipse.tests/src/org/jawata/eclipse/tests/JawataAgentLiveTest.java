package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.jawata.eclipse.access.EngineAddress;
import org.jawata.eclipse.chat.PreferencesChatSettings;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.eclipse.run.HostRuns;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 25a, steps 25a.3 and 25a.4, the live half: ONE turn of jawata's own agent in the Eclipse chat with a real brain (the
 * vendor agent that {@code JAWATA_LIVE_AGENT} names, started as {@code <it> acp}). A program is running for jawata (started through
 * the host's run port, as the person's "Run for jawata" does); the person has set no chat agent; the brain is asked to attach to the
 * program with the debug tool and to tell the value of the program's counter at a line. It costs tokens and needs the person's login,
 * so every build leaves it out (the profile in this module's pom); it runs only when named, with {@code -Djawata.live=true}.
 *
 * <p>Read the printed tool titles, which name the server each call went to: a vendor agent also reads the person's own MCP configuration,
 * and the first run (2026-11-05) answered through the person's {@code jawata-javata-dev} server, not through the engine inside this
 * Eclipse. The assertion shows that a real brain behind jawata's agent in the chat attaches and reads the counter; it does not show which
 * engine it used.</p>
 */
public class JawataAgentLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TURN_MILLIS = 600_000L;
    private static final String SPIN = "package p;\n\npublic class Spin {\n    public static void main(String[] args) throws Exception {\n"
        + "        int ticks = 0;\n        while (true) {\n            ticks++;\n            System.out.println(\"tick \" + ticks);\n"
        + "            Thread.sleep(50);\n        }\n    }\n}\n";
    private static final String PROMPT = "A Java program p.Spin is running on this machine and counts in a local variable named ticks. "
        + "Use the jawata tool debug to find the program (action discover), attach to it by pid, set a line breakpoint in class p.Spin "
        + "on line 8, wait for it to be hit, evaluate the expression ticks, and tell me in one line what number ticks had.";

    @Test
    public void aRealBrainBehindJawatasAgentAttachesToARunningProgramAndReadsAVariableForTheChat() throws Exception {
        String brain = System.getenv("JAWATA_LIVE_AGENT");
        assertNotNull(brain, "JAWATA_LIVE_AGENT names the vendor agent program (Cursor's `agent`): this test is run by name only");
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        IEclipsePreferences engineSettings = InstanceScope.INSTANCE.getNode(EclipseEngine.PLUGIN_ID);
        IEclipsePreferences chatSettings = InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);
        String agentBefore = chatSettings.get(PreferencesChatSettings.AGENT, null);
        ILaunchManager launches = DebugPlugin.getDefault().getLaunchManager();
        IProject project = WorkspaceProjects.java("jawata-livespin-");
        try {
            project.getFolder("src/p").getFile("Spin.java").create(new ByteArrayInputStream(SPIN.getBytes(StandardCharsets.UTF_8)), true, null);
            project.build(IncrementalProjectBuilder.FULL_BUILD, null);
            project.getFolder("launch").create(true, true, null);
            ILaunchConfigurationType type = launches.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);
            ILaunchConfigurationWorkingCopy made = type.newInstance(project.getFolder("launch"), "jawata-livespin");
            made.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, project.getName());
            made.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "p.Spin");
            made.doSave();
            HostRuns.JawataRun run = HostRuns.shared().run("jawata-livespin", HostRuns.PERSON).get(60, TimeUnit.SECONDS);
            System.out.println("jawata-live-agent: program running for jawata, pid " + run.pid());

            chatSettings.remove(PreferencesChatSettings.AGENT);
            engineSettings.put("jawata.agent.brain", JSON.writeValueAsString(Map.of("command", List.of(brain, "acp"))));
            try (HostTestPage page = new HostTestPage(new PreferencesChatSettings(), new RecordingDialogs(), EngineAddress::url)) {
                page.run("window.playLive({\"turns\": 1, \"timeoutMs\": " + TURN_MILLIS + "});");
                page.command("connectAgent", Map.of());
                page.command("sendPrompt", Map.of("text", PROMPT));
                long until = System.currentTimeMillis() + TURN_MILLIS + 60_000L;
                Optional<JsonNode> played = Optional.empty();
                while (played.isEmpty() && System.currentTimeMillis() < until) {
                    played = page.callWith("played");
                    Thread.sleep(500);
                }
                JsonNode answer = played.orElseThrow(() -> new AssertionError("the page never reported the turn; calls: " + page.calls().size()));
                List<String> calls = new ArrayList<>();
                for (JsonNode tool : answer.path("tools")) {
                    calls.add(tool.path("title").asText() + " [" + tool.path("status").asText() + "]");
                }
                StringBuilder said = new StringBuilder();
                answer.path("body").path("messages").forEach(message -> {
                    JsonNode update = message.path("update");
                    if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                        said.append(update.path("content").path("text").asText());
                    }
                });
                System.out.println("jawata-live-agent: tool calls: " + calls);
                System.out.println("jawata-live-agent: the agent said: " + said);
                assertEquals(1, answer.path("ended").asInt(), "the turn ended; the calls: " + calls);
                assertTrue(calls.stream().anyMatch(call -> call.contains("debug") && call.endsWith("[completed]")), "the brain called the debug tool through jawata: " + calls);
                assertTrue(said.toString().matches("(?s).*[1-9][0-9]*.*"), "and told a number: " + said);
            }
        } finally {
            engineSettings.remove("jawata.agent.brain");
            if (agentBefore != null) {
                chatSettings.put(PreferencesChatSettings.AGENT, agentBefore);
            }
            for (ILaunch launch : launches.getLaunches()) {
                if (!launch.isTerminated()) {
                    launch.terminate();
                }
                launches.removeLaunch(launch);
            }
            Job.getJobManager().cancel(HostRuns.FAMILY);
            Job.getJobManager().join(HostRuns.FAMILY, null);
            WorkspaceProjects.deleteQuietly(project);
        }
    }
}
