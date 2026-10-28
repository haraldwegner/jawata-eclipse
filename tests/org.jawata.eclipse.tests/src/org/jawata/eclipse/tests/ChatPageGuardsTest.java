package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.jawata.eclipse.chat.ChatPage;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, audit findings of C21. Three things the chat relies on without anyone noticing when they break: the browser cannot be
 * sent away from the chat page (a page loaded in its place would inherit the bridge, which can start programs); a message sent before
 * the page has loaded still reaches it; and the real host offers the agent jawata's tools when it writes the request that opens a session.
 */
public class ChatPageGuardsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void aLinkOrAWindowCannotTakeTheBrowserAwayFromTheChat() throws Exception {
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent())) {
            String chat = page.url();
            page.run("window.location.href = 'file:///etc/hostname';");
            Thread.sleep(1500);
            assertEquals(chat, page.url(), "a navigation to another file is refused");
            page.run("window.open('file:///etc/hostname');");
            Thread.sleep(1500);
            assertEquals(chat, page.url(), "and so is a window the page asks for");
            assertEquals("function", page.evaluate("typeof window.jawataHostCall"), "the page is still the chat, with its bridge");
        }
    }

    @Test
    public void aMessageSentBeforeThePageHasLoadedStillReachesIt() throws Exception {
        AtomicReference<Shell> shell = new AtomicReference<>();
        try {
            ChatPage page = UiThread.call(() -> {
                Shell created = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
                created.setLayout(new FillLayout());
                shell.set(created);
                ChatPage shown = new ChatPage(created);
                // the page cannot have loaded yet: this thread is the one that loads it
                shown.deliver("{\"kind\":\"theme\",\"css\":\":root { --jc-host-ground: #010203; }\"}");
                created.setSize(600, 400);
                created.open();
                return shown;
            });
            Browser browser = UiThread.call(() -> (Browser) page.getChildren()[0]);
            Await.found("the message sent before the load", () -> "#010203".equals(String.valueOf(UiThread.call(() -> browser.evaluate(
                "return getComputedStyle(document.documentElement).getPropertyValue('--jc-host-ground').trim();")))) ? Optional.of(true) : Optional.empty());
        } finally {
            Display.getDefault().syncExec(() -> {
                if (shell.get() != null) {
                    shell.get().dispose();
                }
            });
        }
    }

    /** A stand-in agent that writes every line it is sent to the file it is given. */
    private static final String RECORDER = "require('readline').createInterface({ input: process.stdin }).on('line', (line) => {"
        + " require('fs').appendFileSync(process.argv[1], line + '\\n'); });";

    private void callFromPage(HostTestPage page, Map<String, Object> op) throws IOException {
        page.run("window.jawataHostCall(" + ChatPage.quote(json.writeValueAsString(op)) + ");");
    }

    @Test
    public void theHostOffersTheAgentJawataWhenItWritesTheRequestThatOpensASession() throws Exception {
        String address = "http://127.0.0.1:4711/mcp";
        Path received = Files.createTempFile("jawata-offer-", ".log");
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent(), new RecordingDialogs(), () -> Optional.of(address))) {
            callFromPage(page, Map.of("op", "spawn", "id", "agent_1", "command", "node", "args", List.of("-e", RECORDER, received.toString()),
                "env", Map.of(), "cwd", ""));
            callFromPage(page, Map.of("op", "write", "id", "agent_1",
                "message", "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\",\"mcpServers\":[]}}"));
            String line = Await.found("the request at the agent", () -> {
                try {
                    return Files.readAllLines(received).stream().findFirst();
                } catch (IOException notYet) {
                    return Optional.empty();
                }
            });
            JsonNode servers = json.readTree(line).path("params").path("mcpServers");
            assertEquals(1, servers.size(), "the agent was offered one server: " + line);
            assertEquals("jawata", servers.get(0).path("name").asText(), line);
            assertEquals(address, servers.get(0).path("url").asText(), line);
            assertTrue(servers.get(0).path("headers").isEmpty(), "and no key: " + line);
        } finally {
            Files.deleteIfExists(received);
        }
    }
}
