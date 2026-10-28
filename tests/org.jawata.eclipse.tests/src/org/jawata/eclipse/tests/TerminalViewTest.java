package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.jawata.eclipse.terminal.TerminalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Plan Stage 13c, step 1: the jawata terminal view opens from its id and its page loads in the real Browser. */
public class TerminalViewTest {

    @AfterEach
    public void closeTheView() {
        UiThread.call(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
            page.hideView(page.findView(TerminalView.ID));
            return null;
        });
    }

    @Test
    public void theViewOpensAndItsPageLoads() throws Exception {
        AtomicReference<TerminalView> view = new AtomicReference<>();
        UiThread.call(() -> {
            try {
                var intro = PlatformUI.getWorkbench().getIntroManager().getIntro();
                if (intro != null) {
                    PlatformUI.getWorkbench().getIntroManager().closeIntro(intro);
                }
                view.set((TerminalView) PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage().showView(TerminalView.ID));
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
            return null;
        });
        assertNotNull(view.get(), "the view opens from its id");
        String shown = Await.found("the page's text", () -> java.util.Optional.ofNullable(UiThread.call(() -> {
            String text = String.valueOf(view.get().browser().getText());
            return text.contains("jawata terminal") ? text : null;
        })));
        assertTrue(shown.contains("jawata terminal"), shown);
        // and the page really holds an xterm.js terminal, not only a title
        Number terminals;
        try {
            terminals = Await.found("an xterm.js terminal in the page", () -> java.util.Optional.ofNullable(UiThread.call(() -> {
                Object count = view.get().browser().evaluate("return document.querySelectorAll('.xterm').length;");
                return count instanceof Number number && number.intValue() >= 1 ? number : null;
            })));
        } catch (AssertionError missing) {
            Object said = UiThread.call(() -> view.get().browser().evaluate(
                "return typeof Terminal + '|' + typeof FitAddon + '|' + document.body.innerHTML.length + '|' + document.location.href;"));
            throw new AssertionError(missing.getMessage() + "; the page says: " + said, missing);
        }
        assertTrue(terminals.intValue() >= 1, "the page shows an xterm.js terminal");
    }
}
