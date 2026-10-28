package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.jawata.eclipse.chat.ChatHost;
import org.jawata.eclipse.chat.ChatPage;
import org.jawata.eclipse.chat.ChatPreferencePage;
import org.jawata.eclipse.chat.ChatSettings;
import org.jawata.eclipse.chat.PreferencesChatSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The chat picks up where the person left off (plan Stage 23, his words of 2026-10-28: "if I open and have the histories I should be able
 * to move on where I left"). The host remembers the conversation a prompt, a load or a resume belongs to, and when the page starts it asks
 * the page to reopen that conversation, unless the person turned it off, none was kept or no agent is set. The preferences hold it, and
 * the preference page has the two new choices.
 */
public class ChatLastConversationTest {

    private static final String STARTED = "{\"op\":\"started\",\"contract\":6}";
    private static final List<String> AN_AGENT = List.of("some-agent");

    private final Preferences node = InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);
    private final List<Path> files = new ArrayList<>();

    @AfterEach
    public void leaveNothingSet() throws Exception {
        node.remove(PreferencesChatSettings.PLACEMENT);
        node.remove(PreferencesChatSettings.CONTINUE_LAST_CONVERSATION);
        node.remove(PreferencesChatSettings.LAST_SESSION);
        node.flush();
        for (Path file : files) {
            Files.deleteIfExists(file);
        }
    }

    /** A chat setup that keeps what the host tells it, as the preferences do, and says what the person wants. */
    private static final class KeptSettings implements ChatSettings {

        private final List<String> command;
        private final boolean continueLast;
        private String last;

        KeptSettings(List<String> command, boolean continueLast, String last) {
            this.command = command;
            this.continueLast = continueLast;
            this.last = last;
        }

        @Override
        public List<String> agentCommand() {
            return command;
        }

        @Override
        public Map<String, String> agentEnvironment() {
            return Map.of();
        }

        @Override
        public Path workingFolder() {
            return FixedChatSettings.withoutAgent().workingFolder();
        }

        @Override
        public String agentSetup() {
            return "the test settings";
        }

        @Override
        public Runnable onChange(Runnable listener) {
            return () -> { };
        }

        @Override
        public String clientVersion() {
            return "0.0.0-test";
        }

        @Override
        public boolean continueLastConversation() {
            return continueLast;
        }

        @Override
        public String lastSession() {
            return last;
        }

        @Override
        public void rememberSession(String sessionId) {
            last = sessionId;
        }
    }

    /** A page that only records what Java delivers to it, served by a host with the given setup. */
    private final class ListeningPage implements AutoCloseable {

        final Shell shell;
        final ChatPage page;
        final ChatHost host;

        ListeningPage(ChatSettings settings) throws Exception {
            Path html = Files.createTempFile("jawata-listening-", ".html");
            files.add(html);
            Files.writeString(html, "<html><script>window.delivered = []; window.jawataHostDeliver = function (m) { window.delivered.push(m); };</script></html>");
            AtomicReference<Shell> shown = new AtomicReference<>();
            AtomicReference<ChatPage> paged = new AtomicReference<>();
            AtomicReference<ChatHost> serving = new AtomicReference<>();
            Display.getDefault().syncExec(() -> {
                Shell created = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
                created.setLayout(new FillLayout());
                ChatPage chatPage = new ChatPage(created, html.toUri().toString());
                shown.set(created);
                paged.set(chatPage);
                serving.set(new ChatHost(chatPage, settings, line -> { }, Optional::empty, new RecordingDialogs()));
                created.open();
            });
            shell = shown.get();
            page = paged.get();
            host = serving.get();
            Await.found("the listening page loaded", () -> UiThread.call(page::isLoaded) ? Optional.of(true) : Optional.empty());
        }

        /** Tells the host what the page tells it when the chat has started. */
        void start() {
            UiThread.call(() -> host.handle(STARTED));
        }

        /** Tells the host that the page sent a message to the agent. */
        void write(String message) throws Exception {
            String call = new ObjectMapper().writeValueAsString(Map.of("op", "write", "id", "agent", "message", message));
            UiThread.call(() -> host.handle(call));
        }

        /** What the page has been told so far, as text. */
        String delivered() {
            Browser browser = UiThread.call(() -> (Browser) page.getChildren()[0]);
            return String.valueOf(UiThread.call(() -> browser.evaluate("return JSON.stringify(window.delivered);")));
        }

        /** Waits until the page has been told something that contains this text. */
        String awaitDelivered(String text) throws Exception {
            return Await.found("the page told " + text, () -> {
                String seen = delivered();
                return seen.contains(text) ? Optional.of(seen) : Optional.empty();
            });
        }

        @Override
        public void close() {
            Display.getDefault().syncExec(() -> {
                host.close();
                shell.dispose();
            });
        }
    }

    @Test
    public void whenThePageStartsItIsAskedToReopenTheLastConversationIfThePersonWantsThat() throws Exception {
        try (ListeningPage listening = new ListeningPage(new KeptSettings(AN_AGENT, true, "s-1"))) {
            listening.start();
            String seen = listening.awaitDelivered("openSession");
            assertTrue(seen.contains("s-1"), "the command carries the conversation's id: " + seen);
        }
    }

    @Test
    public void theLastConversationIsNotReopenedWhenTurnedOffWhenNoneWasKeptOrWhenNoAgentIsSet() throws Exception {
        List<ChatSettings> unwanted = List.of(new KeptSettings(AN_AGENT, false, "s-1"), new KeptSettings(AN_AGENT, true, ""), new KeptSettings(List.of(), true, "s-1"));
        for (ChatSettings settings : unwanted) {
            try (ListeningPage listening = new ListeningPage(settings)) {
                listening.start();
                // a command sent after the start arrives after anything the start sent, so its arrival shows the start sent nothing
                UiThread.call(() -> {
                    listening.host.command("connectAgent", Map.of());
                    return null;
                });
                String seen = listening.awaitDelivered("connectAgent");
                assertFalse(seen.contains("openSession"), "nothing was reopened: " + seen);
            }
        }
    }

    @Test
    public void aPromptALoadAndAResumeAreRememberedAndANewSessionAloneIsNot() throws Exception {
        KeptSettings settings = new KeptSettings(AN_AGENT, true, "");
        try (ListeningPage listening = new ListeningPage(settings)) {
            listening.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/x\",\"mcpServers\":[]}}");
            assertEquals("", settings.lastSession(), "a session that never had a prompt is not kept");
            listening.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"abc\",\"prompt\":[]}}");
            assertEquals("abc", settings.lastSession(), "a prompt keeps its session");
            listening.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/load\",\"params\":{\"sessionId\":\"def\",\"cwd\":\"/x\"}}");
            assertEquals("def", settings.lastSession(), "a load keeps its session");
            listening.write("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/resume\",\"params\":{\"sessionId\":\"ghi\",\"cwd\":\"/x\"}}");
            assertEquals("ghi", settings.lastSession(), "a resume keeps its session");
            listening.write("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"session/cancel\",\"params\":{\"sessionId\":\"zzz\"}}");
            assertEquals("ghi", settings.lastSession(), "a cancel changes nothing");
        }
    }

    @Test
    public void thePreferencesKeepTheLastConversationAndContinueItUnlessTurnedOff() throws BackingStoreException {
        PreferencesChatSettings settings = new PreferencesChatSettings();
        assertTrue(settings.continueLastConversation(), "continuing is on until the person turns it off");
        assertEquals("", settings.lastSession(), "nothing is kept at first");
        settings.rememberSession("kept-1");
        assertEquals("kept-1", new PreferencesChatSettings().lastSession(), "a conversation kept is read by the next chat");
        node.putBoolean(PreferencesChatSettings.CONTINUE_LAST_CONVERSATION, false);
        assertFalse(new PreferencesChatSettings().continueLastConversation(), "and turned off it stays off");
    }

    private static void collect(Control control, List<Combo> combos, List<Button> checks) {
        if (control instanceof Combo combo) {
            combos.add(combo);
        } else if (control instanceof Button button && (button.getStyle() & SWT.CHECK) != 0) {
            checks.add(button);
        } else if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collect(child, combos, checks);
            }
        }
    }

    @Test
    public void thePreferencePageSavesWhereTheChatOpensAndWhetherToContinue() throws Exception {
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
            List<Combo> combos = new ArrayList<>();
            List<Button> checks = new ArrayList<>();
            UiThread.call(() -> {
                collect(page.get().getControl(), combos, checks);
                return null;
            });
            assertEquals(1, combos.size(), "one choice of where the chat opens");
            assertEquals(1, checks.size(), "one switch for continuing the last conversation");
            assertEquals(0, UiThread.call(() -> combos.get(0).getSelectionIndex()), "with nothing chosen the page says the chat will ask");
            assertTrue(UiThread.call(() -> checks.get(0).getSelection()), "and continuing is on");

            UiThread.call(() -> {
                combos.get(0).select(1);
                checks.get(0).setSelection(false);
                return page.get().performOk();
            });
            assertEquals("editor", node.get(PreferencesChatSettings.PLACEMENT, ""), "the middle is saved");
            assertFalse(node.getBoolean(PreferencesChatSettings.CONTINUE_LAST_CONVERSATION, true), "and continuing is off");

            UiThread.call(() -> {
                combos.get(0).select(2);
                return page.get().performOk();
            });
            assertEquals("side", node.get(PreferencesChatSettings.PLACEMENT, ""), "the side panel is saved");

            UiThread.call(() -> {
                combos.get(0).select(0);
                return page.get().performOk();
            });
            assertEquals("", node.get(PreferencesChatSettings.PLACEMENT, ""), "asking again clears the choice");
        } finally {
            Display.getDefault().syncExec(() -> shell.get().dispose());
        }
    }
}
