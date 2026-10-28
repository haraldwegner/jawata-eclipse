package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.jawata.eclipse.chat.ChatHost;
import org.jawata.eclipse.chat.ChatPage;
import org.jawata.eclipse.chat.ChatTheme;
import org.jawata.eclipse.chat.ChatView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Plan Stage 21, step 21.10 (ledger 28i-C19-6): the theme the page receives is Eclipse's as it is, value by value, and an open chat follows
 * a change of it. The first test reads each input out of the stylesheet of a real page whose surface the test has coloured, and compares it
 * with the value worked out here from SWT and JFace; the second shows the real chat view and watches its page restyle itself, once on the
 * page's own refresh and once on the system's settings event, which is the one a theme switch raises.
 */
@ExtendWith(ChatPlacementSetup.class)
public class ChatThemePageTest {

    private static final RGB GROUND = new RGB(0x12, 0x34, 0x56);
    private static final RGB TEXT = new RGB(0xe0, 0xd0, 0xc0);

    private static String hex(RGB rgb) {
        return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
    }

    private static RGB mix(RGB over, RGB under, double share) {
        return new RGB((int) Math.round(over.red * share + under.red * (1 - share)), (int) Math.round(over.green * share + under.green * (1 - share)),
            (int) Math.round(over.blue * share + under.blue * (1 - share)));
    }

    /** The inputs of a stylesheet, by name. */
    private static Map<String, String> inputsOf(String css) {
        Map<String, String> inputs = new TreeMap<>();
        for (String declaration : css.substring(css.indexOf('{') + 1, css.lastIndexOf('}')).split(";")) {
            if (declaration.isBlank()) {
                continue;
            }
            int colon = declaration.indexOf(':');
            inputs.put(declaration.substring(0, colon).trim().replace("--jc-host-", ""), declaration.substring(colon + 1).trim());
        }
        return inputs;
    }

    @Test
    public void eachInputIsTheValueOfEclipseItNames() {
        Display display = Display.getDefault();
        AtomicReference<Shell> shell = new AtomicReference<>();
        try {
            ChatPage page = UiThread.call(() -> {
                Shell created = new Shell(display, SWT.SHELL_TRIM);
                shell.set(created);
                ChatPage shown = new ChatPage(created, "about:blank");
                shown.setBackground(new Color(display, GROUND));
                shown.setForeground(new Color(display, TEXT));
                return shown;
            });
            Map<String, String> inputs = inputsOf(UiThread.call(() -> {
                ChatHost host = new ChatHost(page, FixedChatSettings.withoutAgent(), line -> { }, java.util.Optional::empty, new RecordingDialogs());
                try {
                    return host.themeCss();
                } finally {
                    host.close();
                }
            }));
            RGB selection = UiThread.call(() -> display.getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB());
            RGB selectionText = UiThread.call(() -> display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT).getRGB());
            String defaultFont = UiThread.call(() -> JFaceResources.getDefaultFont().getFontData()[0].getName());
            int defaultSize = UiThread.call(() -> JFaceResources.getDefaultFont().getFontData()[0].getHeight());
            String textFont = UiThread.call(() -> JFaceResources.getTextFont().getFontData()[0].getName());

            assertEquals(ChatTheme.INPUTS.keySet(), inputs.keySet(), "every input is set, and only those");
            assertEquals(hex(GROUND), inputs.get("ground"), "ground is the surface the chat sits on");
            assertEquals(hex(TEXT), inputs.get("text"), "text is the colour of text on that surface");
            assertEquals(hex(mix(TEXT, GROUND, 0.22)), inputs.get("edge"), "edge is the text mixed into the surface");
            assertEquals(hex(selection), inputs.get("accent"), "accent is the selection");
            assertEquals(hex(selectionText), inputs.get("on-accent"), "on-accent is the text on the selection");
            assertEquals(hex(mix(selection, GROUND, 0.25)), inputs.get("mine"), "mine is the selection lightly on the surface");
            assertEquals(hex(TEXT), inputs.get("mine-text"), "mine-text is the text of the surface");
            assertEquals("\"" + defaultFont + "\", sans-serif", inputs.get("font"), "font is the workbench's default font");
            assertEquals(defaultSize + "pt", inputs.get("size"), "size is that font's size in points");
            assertEquals("\"" + textFont + "\", sans-serif", inputs.get("mono"), "mono is the workbench's text font");
            assertEquals("#2e9d57", inputs.get("ok"), "ok: Eclipse has no colour for success");
            assertEquals("#c98a1a", inputs.get("warn"), "warn: Eclipse has no colour for a warning");
            // the five that come from widgets Eclipse styles and from its colour registry, each read here the way a person's eye would
            // find them: a list's background, a text field's two colours, the theme's link and error colours
            Map<String, String> widgets = UiThread.call(() -> {
                Shell probe = new Shell(display, SWT.NO_TRIM);
                try {
                    org.eclipse.swt.widgets.Composite holder = new org.eclipse.swt.widgets.Composite(probe, SWT.NONE);
                    org.eclipse.swt.widgets.Text text = new org.eclipse.swt.widgets.Text(holder, SWT.BORDER);
                    org.eclipse.swt.widgets.Tree tree = new org.eclipse.swt.widgets.Tree(holder, SWT.NONE);
                    Color link = JFaceResources.getColorRegistry().get(org.eclipse.jface.preference.JFacePreferences.HYPERLINK_COLOR);
                    Color error = JFaceResources.getColorRegistry().get(org.eclipse.jface.preference.JFacePreferences.ERROR_COLOR);
                    Map<String, String> read = new TreeMap<>();
                    read.put("raised", hex(tree.getBackground().getRGB()));
                    read.put("field", hex(text.getBackground().getRGB()));
                    read.put("field-text", hex(text.getForeground().getRGB()));
                    read.put("link", hex((link != null ? link : display.getSystemColor(SWT.COLOR_LINK_FOREGROUND)).getRGB()));
                    read.put("bad", hex((error != null ? error : display.getSystemColor(SWT.COLOR_RED)).getRGB()));
                    return read;
                } finally {
                    probe.dispose();
                }
            });
            for (Map.Entry<String, String> expected : widgets.entrySet()) {
                assertEquals(expected.getValue(), inputs.get(expected.getKey()), expected.getKey() + " is the value of its widget or registry entry");
            }
            assertEquals(5, widgets.size(), "all five are checked");
        } finally {
            Display.getDefault().syncExec(() -> {
                if (shell.get() != null) {
                    shell.get().dispose();
                }
            });
        }
    }

    /** The event the system raises when the person changes its settings, such as a light or dark switch; SWT has no public way to raise it. */
    private static void raiseSettingsEvent(Display display) {
        try {
            java.lang.reflect.Method send = Display.class.getDeclaredMethod("sendEvent", int.class, Event.class);
            send.setAccessible(true);
            send.invoke(display, SWT.Settings, new Event());
        } catch (ReflectiveOperationException cannot) {
            throw new AssertionError("the settings event cannot be raised in this SWT: " + cannot, cannot);
        }
    }

    private static String groundInThePage(Browser browser) {
        return String.valueOf(UiThread.call(() -> browser.evaluate(
            "return getComputedStyle(document.documentElement).getPropertyValue('--jc-host-ground').trim();")));
    }

    @Test
    public void anOpenChatFollowsAChangeOfTheTheme() throws Exception {
        AtomicReference<IWorkbenchPage> shown = new AtomicReference<>();
        Display display = Display.getDefault();
        try {
            ChatView view = UiThread.call(() -> {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                shown.set(page);
                try {
                    return (ChatView) page.showView(ChatView.ID);
                } catch (Exception failed) {
                    throw new AssertionError("the chat view cannot be shown: " + failed, failed);
                }
            });
            ChatPage page = view.page();
            assertNotNull(page, "the view shows its page");
            Browser browser = UiThread.call(() -> (Browser) page.getChildren()[0]);
            String first = Await.found("the theme the page asked for when it started", () -> {
                String ground = groundInThePage(browser);
                return ground.matches("#[0-9a-f]{6}") ? Optional.of(ground) : Optional.empty();
            });
            assertEquals(UiThread.call(() -> hex(page.getBackground().getRGB())), first, "the page started in the colour of the surface it sits on");

            UiThread.call(() -> {
                page.setBackground(new Color(display, GROUND));
                view.host().refreshTheme();
                return null;
            });
            Await.found("the page restyled by the refresh", () -> hex(GROUND).equals(groundInThePage(browser)) ? Optional.of(true) : Optional.empty());

            RGB other = new RGB(0x65, 0x43, 0x21);
            UiThread.call(() -> {
                page.setBackground(new Color(display, other));
                raiseSettingsEvent(display);
                return null;
            });
            Await.found("the page restyled by the system's settings event", () -> hex(other).equals(groundInThePage(browser)) ? Optional.of(true) : Optional.empty());
        } finally {
            IWorkbenchPage page = shown.get();
            if (page != null) {
                Display.getDefault().syncExec(() -> page.hideView(page.findViewReference(ChatView.ID)));
            }
        }
    }
}
