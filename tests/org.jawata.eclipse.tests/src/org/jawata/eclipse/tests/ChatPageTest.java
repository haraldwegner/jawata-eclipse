package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.jawata.eclipse.chat.ChatHost;
import org.jawata.eclipse.chat.ChatPage;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, steps 21.2 and 21.4: the chat page this plug-in ships loads in a real SWT Browser and starts the chat on the host
 * bridge: it asks Java for its configuration, mounts the view, and says it started with the view contract number it was built against
 * (the number {@code scripts/build-chat-page.sh} read from chat-core into {@code page/view-contract.json}). The page is the real built
 * one and Java's side is the real {@link ChatHost}; only the setup is the test's.
 */
public class ChatPageTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** The first call the page made with this op, and where in the order it came. */
    private int positionOf(String op) {
        for (int i = 0; i < calls.size(); i++) {
            try {
                if (op.equals(json.readTree(calls.get(i)).path("op").asText())) {
                    return i;
                }
            } catch (IOException unreadable) {
                return -1;
            }
        }
        return -1;
    }

    private static String pageState(ChatPage page) {
        StringBuilder out = new StringBuilder();
        Display.getDefault().syncExec(() -> {
            Browser browser = (Browser) page.getChildren()[0];
            out.append("loaded=").append(page.isLoaded()).append(" url=").append(browser.getUrl());
            for (String probe : new String[] {"typeof window.jawataChatPage", "document.getElementById('root').childElementCount",
                "typeof jawataHostCall", "document.readyState"}) {
                try {
                    out.append(" | ").append(probe).append(" = ").append(browser.evaluate("return " + probe + ";"));
                } catch (RuntimeException failed) {
                    out.append(" | ").append(probe).append(" threw ").append(failed.getMessage());
                }
            }
        });
        return out.toString();
    }

    @Test
    public void aPageThatSpeaksAnotherContractThanTheBuildRecordedIsReportedToThePerson() throws Exception {
        int built = ChatPage.builtContract().orElseThrow();
        RecordingDialogs dialogs = new RecordingDialogs();
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent(), dialogs)) {
            assertEquals(List.of(), dialogs.asked, "a page of the contract it was built against is not reported");
            page.run("window.jawataHostCall(JSON.stringify({op: 'started', contract: " + (built + 1) + "}));");
            Await.found("the report", () -> dialogs.asked.isEmpty() ? Optional.empty() : Optional.of(dialogs.asked.get(0)));
        }
        assertEquals(1, dialogs.asked.size(), "one report: " + dialogs.asked);
        assertTrue(dialogs.asked.get(0).startsWith("error: The chat page speaks view contract " + (built + 1) + ", but this plug-in was built against " + built),
            dialogs.asked.get(0));
    }

    @Test
    public void thePageAsksForItsConfigurationMountsTheViewAndSaysItStartedWithTheContractItWasBuiltAgainst() throws Exception {
        int recorded = ChatPage.builtContract().orElse(-1);
        assertTrue(recorded > 0, "the build recorded the contract number the page speaks: " + recorded);
        AtomicReference<Shell> shell = new AtomicReference<>();
        AtomicReference<ChatPage> page = new AtomicReference<>();
        AtomicReference<ChatHost> host = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            Shell created = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
            created.setLayout(new FillLayout());
            ChatPage shown = new ChatPage(created);
            ChatHost serving = new ChatHost(shown, FixedChatSettings.withoutAgent(), line -> { }, Optional::empty, new RecordingDialogs());
            shown.setHandler(call -> {
                calls.add(call);
                return serving.handle(call);
            });
            page.set(shown);
            host.set(serving);
            created.setSize(900, 700);
            created.open();
            shell.set(created);
        });
        try {
            try {
                Await.found("the page's started call", () -> positionOf("started") >= 0 ? Optional.of(calls) : Optional.empty());
            } catch (AssertionError silent) {
                throw new AssertionError(silent.getMessage() + " | calls: " + calls + " | " + pageState(page.get()), silent);
            }

            JsonNode started = json.readTree(calls.get(positionOf("started")));
            assertEquals(recorded, started.path("contract").asInt(-1), "it carries the contract it was built against: " + started);
            assertTrue(positionOf("config") >= 0 && positionOf("config") < positionOf("started"), "it asked for its configuration before it started: " + calls);
            Object mounted = UiThread.call(() ->((Browser) page.get().getChildren()[0]).evaluate("return document.getElementById('root').childElementCount;"));
            assertTrue(((Number) mounted).intValue() > 0, "the view is mounted in the page: " + pageState(page.get()));
        } finally {
            Display.getDefault().syncExec(() -> {
                host.get().close();
                shell.get().dispose();
            });
        }
    }
}
