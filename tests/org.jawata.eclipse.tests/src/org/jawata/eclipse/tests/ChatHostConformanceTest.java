package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.jawata.eclipse.chat.ChatHost;
import org.jawata.eclipse.chat.ChatPage;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, step 21.4: chat-core's own host-port checks (what a host must do about spawning, ending and telling an agent's end, what
 * it answers at once, what a notifier does), run in the Eclipse page against the real adapter and the real Java relay behind it. The
 * checks are chat-core's, bundled with the adapter by {@code scripts/build-chat-page.sh} into {@code .work/chat-conformance/}; a host that
 * fails one of them loses an agent's death or a settings read without anyone noticing.
 */
public class ChatHostConformanceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** The conformance page the build wrote, found from the folder the tests run in up to the repository that holds .work. */
    private static Path conformancePage() {
        for (Path folder = Path.of("").toAbsolutePath(); folder != null; folder = folder.getParent()) {
            Path page = folder.resolve(".work/chat-conformance/conformance.html");
            if (Files.isRegularFile(page)) {
                return page;
            }
        }
        throw new AssertionError("no .work/chat-conformance/conformance.html above " + Path.of("").toAbsolutePath() + ": run scripts/build-chat-page.sh");
    }

    private Optional<JsonNode> callWith(String op) {
        for (String call : calls) {
            try {
                JsonNode read = json.readTree(call);
                if (op.equals(read.path("op").asText())) {
                    return Optional.of(read);
                }
            } catch (IOException unreadable) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Test
    public void theEclipseAdapterPassesChatCoresHostPortChecks() throws Exception {
        AtomicReference<Shell> shell = new AtomicReference<>();
        AtomicReference<ChatPage> page = new AtomicReference<>();
        AtomicReference<ChatHost> host = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            Shell created = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
            created.setLayout(new FillLayout());
            ChatPage shown = new ChatPage(created, conformancePage().toUri().toString());
            ChatHost serving = new ChatHost(shown, FixedChatSettings.withoutAgent(), line -> { });
            shown.setHandler(call -> {
                calls.add(call);
                return serving.handle(call);
            });
            page.set(shown);
            host.set(serving);
            created.setSize(600, 400);
            created.open();
            shell.set(created);
        });
        try {
            Await.found("the conformance page's started call", () -> callWith("started"));

            String spec = "{\"running\": {\"command\": \"node\", \"args\": [\"-e\", \"setInterval(() => {}, 1000);\"]},"
                + " \"cannotStart\": {\"command\": \"jawata-no-such-agent-program\"}}";
            Display.getDefault().syncExec(() -> ((Browser) page.get().getChildren()[0]).execute("window.runConformance(" + spec + ");"));

            JsonNode result = Await.found("the conformance result", () -> callWith("conformance"));
            List<String> failures = new ArrayList<>();
            result.path("failures").forEach(failure -> failures.add(failure.asText()));
            assertEquals(List.of(), failures, "what the Eclipse host does not do that chat-core says a host must");
            assertTrue(callWith("spawn").isPresent(), "and the checks did start agents through Java: " + calls.size() + " calls");
        } finally {
            Display.getDefault().syncExec(() -> {
                host.get().close();
                shell.get().dispose();
            });
        }
    }
}
