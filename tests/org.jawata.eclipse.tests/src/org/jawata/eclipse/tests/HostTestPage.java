package org.jawata.eclipse.tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.jawata.eclipse.chat.ChatSettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The host test page (scripts/chat-page/conformance.js, built into .work/chat-conformance/) shown in a shell and served by the real
 * {@link ChatHost}, with every call the page makes recorded. A test starts a check in the page, waits for the call that carries its
 * result, and closes this.
 */
final class HostTestPage implements AutoCloseable {

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Shell shell;
    private final ChatPage page;
    private final ChatHost host;

    /**
     * Opens the page and waits for it to start.
     *
     * @param settings the chat setup the host serves
     */
    HostTestPage(ChatSettings settings) {
        this(settings, new RecordingDialogs());
    }

    /**
     * Opens the page with the given way of asking the person, and waits for it to start.
     *
     * @param settings the chat setup the host serves
     * @param dialogs how the chat's questions are shown and answered
     */
    HostTestPage(ChatSettings settings, RecordingDialogs dialogs) {
        AtomicReference<Shell> shown = new AtomicReference<>();
        AtomicReference<ChatPage> paged = new AtomicReference<>();
        AtomicReference<ChatHost> serving = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            Shell created = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
            created.setLayout(new FillLayout());
            ChatPage chatPage = new ChatPage(created, builtPage().toUri().toString());
            ChatHost chatHost = new ChatHost(chatPage, settings, line -> { }, Optional::empty, dialogs);
            chatPage.setHandler(call -> {
                calls.add(call);
                return chatHost.handle(call);
            });
            created.setSize(600, 400);
            created.open();
            shown.set(created);
            paged.set(chatPage);
            serving.set(chatHost);
        });
        this.shell = shown.get();
        this.page = paged.get();
        this.host = serving.get();
        waitForCall("started");
    }

    /** The page the build wrote, found from the folder the tests run in up to the repository that holds .work. */
    private static Path builtPage() {
        for (Path folder = Path.of("").toAbsolutePath(); folder != null; folder = folder.getParent()) {
            Path page = folder.resolve(".work/chat-conformance/conformance.html");
            if (Files.isRegularFile(page)) {
                return page;
            }
        }
        throw new AssertionError("no .work/chat-conformance/conformance.html above " + Path.of("").toAbsolutePath() + ": run scripts/build-chat-page.sh");
    }

    /**
     * Runs a script in the page.
     *
     * @param script the script text
     */
    void run(String script) {
        Display.getDefault().syncExec(() -> ((Browser) page.getChildren()[0]).execute(script));
    }

    /** The first call the page made with this op, if it has made one. */
    Optional<JsonNode> callWith(String op) {
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

    /** Waits for the page to make a call with this op, and returns it. */
    JsonNode waitForCall(String op) {
        try {
            return Await.found("the page's \"" + op + "\" call (calls so far: " + calls.size() + ")", () -> callWith(op));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the page's \"" + op + "\" call", interrupted);
        }
    }

    /** Every call the page has made, as text. */
    List<String> calls() {
        return calls;
    }

    @Override
    public void close() {
        Display.getDefault().syncExec(() -> {
            host.close();
            shell.dispose();
        });
    }
}
