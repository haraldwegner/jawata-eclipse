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

        synchronized String typedAll() {
            return String.join("", typed);
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

    /** Key events as the browser makes them for a person at the keyboard: down, press (the character) and up, then Enter. */
    @Test
    public void aKeyPressedInThePageReachesTheSession() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        UiThread.call(() -> view.browser().evaluate(
            "var t = document.querySelector('.xterm-helper-textarea'); t.focus();"
            // as a browser does: a keypress follows a keydown only when the page did not handle the keydown (preventDefault)
            + "function key(type, k, code) { return t.dispatchEvent(new KeyboardEvent(type, { key: k, keyCode: code, which: code,"
            + " charCode: type === 'keypress' ? code : 0, bubbles: true, cancelable: true })); }"
            + "function press(k, down, code) { if (key('keydown', k, down)) { key('keypress', k, code); } key('keyup', k, down); }"
            + "var s = 'ls'; for (var i = 0; i < s.length; i++) { var c = s.charCodeAt(i); press(s[i], c - 32, c); }"
            + "press('Enter', 13, 13); return true;"));
        assertTrue(Await.until(() -> session.typedAll().equals("ls\r")), "the keys went through the page's own handling to the session: " + session.typedAll());
    }

    /** The terminal's area is made smaller and put back as a person does by dragging the edge of its panel; the page sees its own size change and the session is told. */
    @Test
    public void aChangeOfTheViewsSizeReachesTheSession() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0 && session.rows > 0), "the page is ready");
        // the size the person has once the layout has settled: the first size the page reports can be from before the view got its place
        int before = -1;
        for (int same = 0, tries = 0; same < 3 && tries < 50; tries++) {
            Thread.sleep(200);
            int now = session.columns * 1000 + session.rows;
            same = now == before ? same + 1 : 0;
            before = now;
        }
        final int settled = before;
        // The terminal's area in the page is made narrower and lower and the page's own resize event is raised, which is what the browser
        // raises when a person drags the edge of the panel. It does not rely on Eclipse's layout: Eclipse's own pages (Sponsor the Eclipse
        // IDE, Install Java 27 Support) arrive from the network a few seconds after start and rearrange the window at random times.
        // Not tested here: that the browser raises its resize event when its widget is resized (the browser's own behaviour); that is part
        // of his look at C13c, dragging the edge of the panel, and of the OS sessions of Stage 24.
        final String area = "var e = document.getElementById('terminal');";
        final String resized = "window.dispatchEvent(new Event('resize')); return true;";
        UiThread.call(() -> view.browser().evaluate(area + "e.style.right = 'auto'; e.style.bottom = 'auto'; e.style.width = '300px'; e.style.height = '150px';" + resized));
        try {
            assertTrue(Await.until(() -> session.columns * 1000 + session.rows != settled),
                "making the terminal's area smaller changed the size the session was told, from " + settled + " to " + (session.columns * 1000 + session.rows));
        } finally {
            UiThread.call(() -> view.browser().evaluate(area + "e.style.right = ''; e.style.bottom = ''; e.style.width = ''; e.style.height = '';" + resized));
        }
        assertTrue(Await.until(() -> session.columns * 1000 + session.rows == settled),
            "putting the area back told the session its first size again (" + settled + "): " + session.columns + "x" + session.rows);
    }

    /** The look the page has now, as the page reports it: {fontFamily, fontSize (CSS px), background, foreground}. */
    private String look() {
        return String.valueOf(UiThread.call(() -> view.browser().evaluate("return JSON.stringify(jawataTerminal.look());")));
    }

    private static long cssPixels(int points) {
        return Math.round(points * 96.0 / 72.0);
    }

    /** The colour as a page spells it, so the expected text is written here and not copied from the code under test. */
    private static String hex(org.eclipse.swt.graphics.Color colour) {
        return String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
    }

    /** Ledger 28k-A13c-7 b: the font and the colours are Eclipse's, not fixed in the page; a font changed in Eclipse reaches the page. */
    @Test
    public void theFontAndTheColoursFollowEclipse() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        org.eclipse.swt.graphics.FontData font = UiThread.call(() -> org.eclipse.jface.resource.JFaceResources.getTextFont().getFontData()[0]);
        String background = UiThread.call(() -> hex(view.browser().getParent().getBackground()));
        String foreground = UiThread.call(() -> hex(view.browser().getParent().getForeground()));
        String taken = Await.found("the page took Eclipse's look", () -> {
            String now = look();
            return now.contains("\"fontSize\":" + cssPixels(font.getHeight()) + ",") ? Optional.of(now) : Optional.empty();
        });
        assertTrue(taken.contains(font.getName()), "the page's font is the editor text font " + font.getName() + ": " + taken);
        assertTrue(taken.contains("\"background\":\"" + background + "\"") && taken.contains("\"foreground\":\"" + foreground + "\""),
            "the page's colours are the view's (" + background + " on " + foreground + "): " + taken);

        org.eclipse.swt.graphics.FontData larger = new org.eclipse.swt.graphics.FontData(font.getName(), font.getHeight() + 6, font.getStyle());
        UiThread.call(() -> {
            org.eclipse.jface.resource.JFaceResources.getFontRegistry().put(org.eclipse.jface.resource.JFaceResources.TEXT_FONT,
                new org.eclipse.swt.graphics.FontData[] { larger });
            return null;
        });
        try {
            assertTrue(Await.until(() -> look().contains("\"fontSize\":" + cssPixels(larger.getHeight()) + ",")),
                "a larger editor font in Eclipse reached the page: " + look());
        } finally {
            UiThread.call(() -> {
                org.eclipse.jface.resource.JFaceResources.getFontRegistry().put(org.eclipse.jface.resource.JFaceResources.TEXT_FONT,
                    new org.eclipse.swt.graphics.FontData[] { font });
                return null;
            });
        }
    }

    /** Ledger 28k-A13c-7 c: a paste as the browser raises it (a clipboard event on the page's input) reaches the session as typed text. */
    @Test
    public void aPastedTextReachesTheSessionAsTyped() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        UiThread.call(() -> view.browser().evaluate(
            "var t = document.querySelector('.xterm-helper-textarea'); t.focus(); var d = new DataTransfer(); d.setData('text/plain', 'echo pasted');"
            + "t.dispatchEvent(new ClipboardEvent('paste', { clipboardData: d, bubbles: true, cancelable: true })); return true;"));
        assertTrue(Await.until(() -> session.typedContains("echo pasted")), "the pasted text reached the session: " + session.typed);
    }

    /** Ledger 28k-A13c-7 c: what is selected on the screen is what a copy puts on the clipboard, box lines included. */
    @Test
    public void aCopyOfTheSelectedScreenHoldsTheTextOnIt() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        session.say("copy me ┌──┐\r\n");
        Await.found("the text on the screen", () -> Optional.ofNullable(UiThread.call(() -> {
            Object text = view.browser().evaluate("return document.querySelector('.xterm-rows') ? document.querySelector('.xterm-rows').innerText : '';");
            return String.valueOf(text).contains("copy me") ? String.valueOf(text) : null;
        })));
        Object copied = UiThread.call(() -> view.browser().evaluate(
            "term.selectAll(); var d = new DataTransfer();"
            + "document.querySelector('.xterm').dispatchEvent(new ClipboardEvent('copy', { clipboardData: d, bubbles: true, cancelable: true }));"
            + "return d.getData('text/plain');"));
        assertTrue(String.valueOf(copied).contains("copy me ┌──┐"), "a copy of the selected screen holds the text on it: '" + copied + "'");
    }

    /** Ledger 28k-A13c-6: the view is made larger and smaller by Eclipse's own layout (maximised, then restored), with no resize event raised by the test. */
    @Test
    public void aRealChangeOfTheViewsSizeReachesTheSession() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0 && session.rows > 0), "the page is ready");
        int before = -1;
        for (int same = 0, tries = 0; same < 3 && tries < 50; tries++) {
            Thread.sleep(200);
            int now = session.columns * 1000 + session.rows;
            same = now == before ? same + 1 : 0;
            before = now;
        }
        final int settled = before;
        IWorkbenchPage page = UiThread.call(() -> PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage());
        org.eclipse.ui.IViewReference reference = UiThread.call(() -> page.findViewReference(TerminalView.ID));
        UiThread.call(() -> {
            page.setPartState(reference, IWorkbenchPage.STATE_MAXIMIZED);
            return null;
        });
        try {
            assertTrue(Await.until(() -> session.columns * 1000 + session.rows != settled),
                "maximising the view changed the size the session was told, from " + settled + " to " + (session.columns * 1000 + session.rows));
        } finally {
            UiThread.call(() -> {
                page.setPartState(reference, IWorkbenchPage.STATE_RESTORED);
                return null;
            });
        }
        assertTrue(Await.until(() -> session.columns * 1000 + session.rows == settled),
            "restoring the view told the session its first size again (" + settled + "): " + session.columns + "x" + session.rows);
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

    /** Pictures of the terminal as the person sees it, with a prompt, a listing, colours and box lines on it, for the ux seat and for the person to look at. */
    @Test
    public void theTerminalIsRenderedForTheUxCheck() throws Exception {
        assertTrue(Await.until(() -> session.columns > 0), "the page is ready");
        session.say("\u001b[32mharald@laptop\u001b[0m:\u001b[34m~/work/shop\u001b[0m$ ls -l\r\ntotal 12\r\n-rw-r--r-- 1 harald harald 1204 Oct 29 14:02 pom.xml\r\n"
            + "drwxr-xr-x 4 harald harald 4096 Oct 29 14:02 \u001b[34msrc\u001b[0m\r\n┌─────────────┐\r\n│ box lines ✓ │ ███ ░░░\r\n└─────────────┘\r\n"
            + "\u001b[32mharald@laptop\u001b[0m:\u001b[34m~/work/shop\u001b[0m$ ");
        Await.found("the sample on the screen", () -> Optional.ofNullable(UiThread.call(() -> {
            Object text = view.browser().evaluate("return document.querySelector('.xterm-rows') ? document.querySelector('.xterm-rows').innerText : '';");
            return String.valueOf(text).contains("box lines") ? String.valueOf(text) : null;
        })));
        UxPicture.picture("terminal-in-the-bottom-panel.png");
        IWorkbenchPage page = UiThread.call(() -> PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage());
        org.eclipse.ui.IViewReference reference = UiThread.call(() -> page.findViewReference(TerminalView.ID));
        UiThread.call(() -> {
            page.setPartState(reference, IWorkbenchPage.STATE_MAXIMIZED);
            return null;
        });
        try {
            UxPicture.picture("terminal-maximised.png");
        } finally {
            UiThread.call(() -> {
                page.setPartState(reference, IWorkbenchPage.STATE_RESTORED);
                return null;
            });
        }
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
