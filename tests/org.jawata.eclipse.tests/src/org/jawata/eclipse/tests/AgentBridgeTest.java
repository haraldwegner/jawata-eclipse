package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jawata.eclipse.access.EngineAddress;
import org.jawata.eclipse.chat.PreferencesChatSettings;
import org.jawata.eclipse.host.AgentBridge;
import org.jawata.eclipse.host.EngineStartup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Plan Stage 25a, step 25a.4: what the chat starts when the person has set no agent of their own is jawata's agent, and the person who
 * has set one keeps it. The command is the engine's bridge program pointed at the engine's own listener; its jar is a file of the
 * plug-in, put on disk byte for byte (and put back when it is damaged), so nothing is installed and nothing is typed.
 */
public class AgentBridgeTest {

    private IEclipsePreferences chat;
    private String agentBefore;

    @BeforeEach
    public void rememberThePersonsAgent() {
        chat = InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);
        agentBefore = chat.get(PreferencesChatSettings.AGENT, null);
    }

    @AfterEach
    public void putThePersonsAgentBack() {
        if (agentBefore == null) {
            chat.remove(PreferencesChatSettings.AGENT);
        } else {
            chat.put(PreferencesChatSettings.AGENT, agentBefore);
        }
    }

    @Test
    public void theBridgeIsStartedAsJavaDashJarAcpWithTheListenersBaseAddress() {
        List<String> command = AgentBridge.commandFor(Path.of("/jdk/bin/java"), Path.of("/state/agent/jawata.jar"), URI.create("http://127.0.0.1:41234/mcp"));

        assertEquals(List.of("/jdk/bin/java", "-jar", "/state/agent/jawata.jar", "--acp", "--url", "http://127.0.0.1:41234"), command,
            "the bridge is given the listener, not the /mcp route of it: it appends the agent's own route");
    }

    @Test
    public void anAddressThatIsNotTheMcpRouteIsGivenAsItIs() {
        List<String> command = AgentBridge.commandFor(Path.of("java"), Path.of("jawata.jar"), URI.create("http://127.0.0.1:9"));

        assertEquals("http://127.0.0.1:9", command.get(command.size() - 1));
    }

    @Test
    public void theEngineInsideEclipseGivesTheCommandWithAJarThatIsThePluginsOwnCopy() throws Exception {
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));

        List<String> command = EngineAddress.agentCommand().orElseThrow(() -> new AssertionError("no agent command although the listener is up"));

        assertEquals(Path.of(System.getProperty("java.home"), "bin", "java").toString(), command.get(0), "the Java Eclipse itself runs on");
        Path jar = Path.of(command.get(command.indexOf("-jar") + 1));
        assertTrue(Files.isRegularFile(jar), "the jar is on disk: " + jar);
        var carried = FrameworkUtil.getBundle(AgentBridge.class).getEntry("agent/jawata.jar");
        try (var in = carried.openStream()) {
            assertTrue(Arrays.equals(in.readAllBytes(), Files.readAllBytes(jar)), "and it is the plug-in's own copy, byte for byte");
        }
        assertEquals("--acp", command.get(command.indexOf("-jar") + 2));
        String url = command.get(command.indexOf("--url") + 1);
        assertTrue(EngineAddress.url().orElseThrow().startsWith(url) && !url.endsWith("/mcp"), "pointed at the engine's own listener: " + url);
    }

    @Test
    public void aDamagedJarIsPutBackWhenTheCommandIsAskedForAgain() throws Exception {
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        Path jar = Path.of(EngineAddress.agentCommand().orElseThrow().get(2));
        byte[] whole = Files.readAllBytes(jar);
        Files.write(jar, new byte[] {1, 2, 3});

        EngineAddress.agentCommand();

        assertTrue(Arrays.equals(whole, Files.readAllBytes(jar)), "the damaged bridge jar was replaced by the plug-in's copy");
    }

    @Test
    public void withNoAgentOfTheirOwnThePersonGetsJawatasAgentWithNothingToSetUp() throws Exception {
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        chat.remove(PreferencesChatSettings.AGENT);
        PreferencesChatSettings settings = new PreferencesChatSettings();

        assertEquals(EngineAddress.agentCommand(), Optional.of(settings.agentCommand()), "the chat's agent is the engine's bridge");
        assertTrue(settings.agentEnvironment().isEmpty(), "and it needs no variable");
    }

    @Test
    public void anAgentThePersonHasSetStaysTheirs() throws Exception {
        Await.found("the engine's listener", () -> EngineStartup.running().flatMap(engine -> engine.endpoint().reached()));
        chat.put(PreferencesChatSettings.AGENT, "{\"command\": \"npx\", \"args\": [\"-y\", \"some-agent\"]}");

        assertEquals(List.of("npx", "-y", "some-agent"), new PreferencesChatSettings().agentCommand(), "the person's agent, not jawata's");
    }
}
