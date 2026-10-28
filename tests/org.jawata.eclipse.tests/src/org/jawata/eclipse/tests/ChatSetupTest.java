package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.jawata.eclipse.chat.ChatPreferencePage;
import org.jawata.eclipse.chat.PreferencesChatSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, step 21.8: the person sets the chat up in Eclipse's preferences. The settings are read from there, the page saves them
 * and refuses an agent that cannot be started from what was typed, an open chat follows a change without restarting, and with no agent
 * set the chat's own question tells the person where to set one.
 */
public class ChatSetupTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Preferences node = InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);

    @AfterEach
    public void leaveNothingSet() throws BackingStoreException {
        node.remove(PreferencesChatSettings.AGENT);
        node.remove(PreferencesChatSettings.WORKING_FOLDER);
        node.flush();
    }

    @Test
    public void theSettingsAreReadFromThePreferences() {
        node.put(PreferencesChatSettings.AGENT, "{\"command\": \"npx\", \"args\": [\"-y\", \"some-agent\"], \"env\": {\"A\": \"b\"}}");
        node.put(PreferencesChatSettings.WORKING_FOLDER, "/some/where");
        PreferencesChatSettings settings = new PreferencesChatSettings();

        assertEquals(List.of("npx", "-y", "some-agent"), settings.agentCommand());
        assertEquals(Map.of("A", "b"), settings.agentEnvironment());
        assertEquals(Path.of("/some/where"), settings.workingFolder());

        node.put(PreferencesChatSettings.AGENT, "");
        assertEquals(List.of(), settings.agentCommand(), "no agent set means no command");
    }

    @Test
    public void anAgentThatCannotBeStartedFromWhatWasTypedIsRefusedWithWhatToDo() {
        assertEquals(Optional.empty(), PreferencesChatSettings.problemWith(""), "empty means no agent, which is allowed");
        assertEquals(Optional.empty(), PreferencesChatSettings.problemWith("{\"command\": \"node\"}"));
        assertTrue(PreferencesChatSettings.problemWith("npx -y agent").orElseThrow().contains("JSON"), "text that is not JSON");
        assertTrue(PreferencesChatSettings.problemWith("[1, 2]").orElseThrow().contains("one JSON object"), "JSON that is not an object");
        assertTrue(PreferencesChatSettings.problemWith("{\"args\": []}").orElseThrow().contains("\"command\""), "no program named");
        assertTrue(PreferencesChatSettings.problemWith("{\"command\": \"x\", \"args\": \"-y\"}").orElseThrow().contains("\"args\""), "arguments that are not a list");
    }

    private static List<Text> textsIn(Control control) {
        List<Text> found = new ArrayList<>();
        if (control instanceof Text text) {
            found.add(text);
        } else if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                found.addAll(textsIn(child));
            }
        }
        return found;
    }

    @Test
    public void thePageSavesWhatIsTypedAndRefusesAnAgentThatIsNotOne() throws Exception {
        AtomicReference<Shell> shell = new AtomicReference<>();
        AtomicReference<ChatPreferencePage> page = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            Shell created = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
            created.setLayout(new FillLayout());
            ChatPreferencePage opened = new ChatPreferencePage();
            opened.init(PlatformUI.getWorkbench());
            opened.createControl(created);
            shell.set(created);
            page.set(opened);
        });
        try {
            List<Text> texts = onThread(() -> textsIn(page.get().getControl()));
            assertEquals(2, texts.size(), "two fields: the agent and the working folder");
            Text agent = texts.get(0);
            Text folder = texts.get(1);

            Display.getDefault().syncExec(() -> agent.setText("npx -y agent"));
            assertFalse(onThread(() -> page.get().performOk()), "text that is not JSON is not saved");
            assertTrue(onThread(() -> page.get().getErrorMessage()).startsWith("The agent:"), "and the page says which field and why");
            assertEquals("", node.get(PreferencesChatSettings.AGENT, ""), "nothing was saved");

            Display.getDefault().syncExec(() -> {
                agent.setText("  {\"command\": \"node\", \"args\": [\"agent.js\"]}  ");
                folder.setText(" /work ");
            });
            assertTrue(onThread(() -> page.get().performOk()), "a usable agent is saved");
            assertEquals("{\"command\": \"node\", \"args\": [\"agent.js\"]}", node.get(PreferencesChatSettings.AGENT, ""));
            assertEquals("/work", node.get(PreferencesChatSettings.WORKING_FOLDER, ""));
        } finally {
            Display.getDefault().syncExec(() -> shell.get().dispose());
        }
    }

    @Test
    public void withNoAgentSetTheChatsOwnQuestionNamesWhereToSetOneAndOpensIt() throws Exception {
        RecordingDialogs dialogs = new RecordingDialogs();
        try (HostTestPage page = new HostTestPage(new PreferencesChatSettings(), dialogs)) {
            page.run("window.connectWithoutAgent();");
            page.waitForCall("connected");
        }
        assertEquals(List.of("confirm: No agent is set. Name its program in the jawata chat preferences. [Configure Agent]", "configureAgent"), dialogs.asked,
            "the question says where, and the person's yes opens it");
    }

    @Test
    public void anOpenChatFollowsAChangeOfTheAgentWithoutRestarting() throws Exception {
        try (HostTestPage page = new HostTestPage(new PreferencesChatSettings())) {
            // the page started with no agent; now the person sets the replay agent in the preferences
            node.put(PreferencesChatSettings.AGENT, agentJson("R1"));
            node.flush();

            page.run("window.playSession({\"answers\": []});");
            JsonNode body = page.waitForCall("played").path("body");

            assertEquals(json.readTree(ChatCheckout.golden("R1").toFile()), body, "the open chat played R1 as chat-core records it");
        }
    }

    private String agentJson(String session) throws IOException {
        List<String> command = ChatCheckout.replayCommand(session);
        Map<String, Object> agent = Map.of("command", command.get(0), "args", command.subList(1, command.size()));
        return json.writeValueAsString(agent);
    }

    private static <T> T onThread(Supplier<T> read) {
        AtomicReference<T> value = new AtomicReference<>();
        Display.getDefault().syncExec(() -> value.set(read.get()));
        return value.get();
    }
}
