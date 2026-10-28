package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.jawata.eclipse.chat.ChatPage;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, step 21.2: the chat page this plug-in ships loads in a real SWT Browser, finds the host function the composite
 * defines, and says ready with the view contract number it was built against (the number {@code scripts/build-chat-page.sh} read
 * from chat-core into {@code page/view-contract.json}). The page is the real built one, not a stand-in.
 */
public class ChatPageTest {

    private static int contractTheBuildRecorded() throws Exception {
        try (InputStream in = FrameworkUtil.getBundle(ChatPage.class).getEntry("page/view-contract.json").openStream()) {
            return new ObjectMapper().readTree(in).path("contract").asInt(-1);
        }
    }

    private static String pageState(ChatPage page) {
        StringBuilder out = new StringBuilder();
        Display.getDefault().syncExec(() -> {
            Browser browser = (Browser) page.getChildren()[0];
            out.append("loaded=").append(page.isLoaded()).append(" url=").append(browser.getUrl());
            for (String probe : new String[] {"typeof window.jawataChatPage", "typeof window.jawataHost", "document.getElementById('root').childElementCount",
                "typeof jawataHostSend", "document.readyState"}) {
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
    public void thePageLoadsInTheBrowserAndSaysReadyWithTheContractItWasBuiltAgainst() throws Exception {
        int recorded = contractTheBuildRecorded();
        assertTrue(recorded > 0, "the build recorded the contract number the page speaks: " + recorded);
        List<String> intents = new CopyOnWriteArrayList<>();
        AtomicReference<Shell> shell = new AtomicReference<>();
        AtomicReference<ChatPage> page = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            Shell created = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
            created.setLayout(new FillLayout());
            ChatPage shown = new ChatPage(created);
            page.set(shown);
            shown.onIntent(intents::add);
            created.setSize(900, 700);
            created.open();
            shell.set(created);
        });
        try {
            String first;
            try {
                first = Await.found("the page's first intent", () -> intents.stream().findFirst());
            } catch (AssertionError silent) {
                throw new AssertionError(silent.getMessage() + " | " + pageState(page.get()), silent);
            }

            JsonNode intent = new ObjectMapper().readTree(first);
            assertEquals("ready", intent.path("type").asText(), "the first thing a page says is ready: " + first);
            assertEquals(recorded, intent.path("contractVersion").asInt(-1), "carrying the contract it was built against: " + first);
        } finally {
            Display.getDefault().syncExec(() -> shell.get().dispose());
        }
    }
}
