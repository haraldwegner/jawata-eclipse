package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.jawata.eclipse.terminal.TerminalSession;
import org.jawata.eclipse.terminal.TerminalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 13c, steps 4 and 5: in the real Browser a key typed into the terminal's page reaches the session behind it, what the session
 * writes appears in the page (box-drawing characters included), and the page's size reaches the session. The session here is a fake that
 * only records, so the page and its bridge are tested without a shell.
 */
public class TerminalPageTest {

    /** A session that records what it was sent and lets the test write to the page. */
    private static final class LoopbackSession implements TerminalSession {
        final List<String> typed = new ArrayList<>();
        volatile int columns;
        volatile int rows;
        volatile boolean closed;
        private volatile Consumer<byte[]> output;

        @Override
        public synchronized void write(byte[] input) {
            typed.add(new String(input, StandardCharsets.UTF_8));
        }

        @Override
        public void onOutput(Consumer<byte[]> listener) {
            output = listener;
        }

        @Override
        public void resize(int columnCount, int rowCount) {
            columns = columnCount;
            rows = rowCount;
        }

        @Override
        public void close() {
            closed = true;
        }

        synchronized boolean typedContains(String text) {
            return typed.stream().anyMatch(each -> each.contains(text));
        }

        void say(String text) {
            output.accept(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private LoopbackSession session;
    private Runnable restore;
    private TerminalView view;

    @BeforeEach
    public void showTheTerminalOverALoopback() throws Exception {
        session = new LoopbackSession();
        restore = TerminalView.sessionsWith(() -> session);
        UiThread.call(() -> {
            try {
                var intro = PlatformUI.getWorkbench().getIntroManager().getIntro();
                if (intro != null) {
                    PlatformUI.getWorkbench().getIntroManager().closeIntro(intro);
                }
                view = (TerminalView) PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage().showView(TerminalView.ID);
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
            return null;
        });
    }

    @AfterEach
    public void putTheShellBack() {
        restore.run();
        UiThread.call(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
            if (page.findView(TerminalView.ID) != null) {
                page.hideView(page.findView(TerminalView.ID));
            }
            return null;
        });
    }

    @Test
    public void theWindowSizeReachesTheSession() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0 && session.rows > 0), "the session was told the size of the page: " + session.columns + "x" + session.rows);
    }

    @Test
    public void aKeyTypedInThePageReachesTheSession() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        UiThread.call(() -> view.browser().evaluate("jawataTerminal.input('ls -l\\r'); return true;"));
        assertTrue(Await.until(() -> session.typedContains("ls -l\r")), "what was typed reached the session: " + session.typed);
    }

    @Test
    public void whatTheSessionWritesAppearsInThePageBoxLinesIncluded() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        session.say("hello ┌──┐ box\r\n");
        String rows = Await.found("the written text in the page", () -> Optional.ofNullable(UiThread.call(() -> {
            Object text = view.browser().evaluate("return document.querySelector('.xterm-rows') ? document.querySelector('.xterm-rows').innerText : '';");
            return String.valueOf(text).contains("hello ┌──┐ box") ? String.valueOf(text) : null;
        })));
        assertTrue(rows.contains("hello ┌──┐ box"), rows);
    }

    @Test
    public void closingTheViewEndsTheSession() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        UiThread.call(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
            page.hideView(view);
            return null;
        });
        assertTrue(Await.until(() -> session.closed), "the shell behind a closed terminal is ended");
    }
}
