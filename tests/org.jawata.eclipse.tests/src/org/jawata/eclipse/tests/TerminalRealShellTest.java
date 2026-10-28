package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.Optional;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.jawata.eclipse.terminal.TerminalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 13c, step 6, the end-to-end half: the person's own shell, in the real page, in the real Eclipse. A command typed into the page is
 * run by the shell and its answer is drawn by the page, and the box-drawing characters Claude Code's screen is made of come out as typed,
 * which Eclipse's own Terminal does not do (measured, docs/terminal-render/). Runs in both Eclipse downloads through the end-to-end script.
 */
public class TerminalRealShellTest {

    private TerminalView view;

    @BeforeEach
    public void showTheTerminal() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "the POSIX commands below are for Linux and macOS");
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
    public void closeTheTerminal() {
        UiThread.call(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
            if (page.findView(TerminalView.ID) != null) {
                page.hideView(page.findView(TerminalView.ID));
            }
            return null;
        });
    }

    private String rows() {
        return String.valueOf(UiThread.call(() -> view.browser().evaluate(
            "return document.querySelector('.xterm-rows') ? document.querySelector('.xterm-rows').innerText : '';")));
    }

    @Test
    public void aTypedCommandIsRunByTheRealShellAndDrawnWithBoxLines() throws Exception {
        // the page says it is ready only when the host's function is in it; give the shell a moment to print its first prompt
        assertTrue(Await.until(() -> rows().strip().length() > 0), "the shell printed its first prompt in the page: " + rows());
        // the frame is typed as octal bytes so the test does not depend on the shell's locale: ┌──┐ is e2 94 8c, e2 94 80, e2 94 80, e2 94 90
        UiThread.call(() -> view.browser().evaluate(
            "jawataTerminal.input(\"echo real-$((6*7)); printf '\\\\342\\\\224\\\\214\\\\342\\\\224\\\\200\\\\342\\\\224\\\\200\\\\342\\\\224\\\\220\\\\n'\\r\"); return true;"));
        String shown = Await.found("the shell's answer in the page", () -> Optional.ofNullable(UiThread.call(() -> {
            String text = rows();
            return text.contains("real-42") && text.contains("┌──┐") ? text : null;
        })));
        assertTrue(shown.contains("real-42"), "the typed command was run: " + shown);
        assertTrue(shown.contains("┌──┐"), "the frame characters came back as bytes and are drawn: " + shown);
    }
}
