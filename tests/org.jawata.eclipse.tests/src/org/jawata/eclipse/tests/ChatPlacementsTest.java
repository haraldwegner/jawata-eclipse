package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.intro.IIntroManager;
import org.eclipse.ui.handlers.IHandlerService;
import org.jawata.eclipse.chat.ChatCommandHandler;
import org.jawata.eclipse.chat.ChatEditor;
import org.jawata.eclipse.chat.ChatEditorInput;
import org.jawata.eclipse.chat.ChatPage;
import org.jawata.eclipse.chat.ChatView;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 23 (spec D10): the chat sits in Eclipse in two places, as a view and as a large page in the editor area, and both are built
 * from the same page and relay. Each is opened by its own command, loads the chat, and takes a chat command; the picture of each is saved
 * where the ux seat reads it ({@code jawata.ux.dir}, else the temp folder).
 */
public class ChatPlacementsTest {

    /** The page the chat opens on, with Eclipse's Welcome page closed: it fills the window in the test workbench and hides the editor area. */
    private static IWorkbenchPage activePage() {
        IIntroManager intro = PlatformUI.getWorkbench().getIntroManager();
        if (intro.getIntro() != null) {
            intro.closeIntro(intro.getIntro());
        }
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    private static void run(String name) {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
        Command command = commands.getCommand(ChatCommandHandler.commandId(name));
        UiThread.call(() -> {
            try {
                return handlers.executeCommand(ParameterizedCommand.generateCommand(command, Map.of()), null);
            } catch (Exception failed) {
                throw new AssertionError("the command " + name + " failed: " + failed, failed);
            }
        });
    }

    private static Browser browserOf(ChatPage page) {
        return UiThread.call(() -> (Browser) page.getChildren()[0]);
    }

    private static void awaitChat(Browser browser, String place) throws Exception {
        Await.found("the chat in the " + place, () -> Optional.ofNullable(UiThread.call(
            () -> Boolean.TRUE.equals(browser.evaluate("return typeof window.jawataChatPage === 'object';")) ? Boolean.TRUE : null)));
    }

    private static void awaitAttach(Browser browser, Path file) throws Exception {
        UiThread.call(() -> browser.evaluate("window.__seen = []; window.jawataChatPage.link.page.onMessage((m) => window.__seen.push(m.type)); return true;"));
        Command attach = PlatformUI.getWorkbench().getService(ICommandService.class).getCommand(ChatCommandHandler.commandId("attachFile"));
        IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
        ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(attach, Map.of(ChatCommandHandler.parameterId("attachFile", "path"), file.toString()));
        UiThread.call(() -> {
            try {
                return handlers.executeCommand(parameterized, null);
            } catch (Exception failed) {
                throw new AssertionError("the command failed: " + failed, failed);
            }
        });
        Await.found("the page's file-attached message", () -> {
            String seen = String.valueOf(UiThread.call(() -> browser.evaluate("return JSON.stringify(window.__seen);")));
            return seen.contains("file-attached") ? Optional.of(seen) : Optional.empty();
        });
    }

    /** Saves a picture of the workbench window, as the person sees it, for the ux seat. */
    private static void picture(String name) throws Exception {
        Path picture = Path.of(System.getProperty("jawata.ux.dir", System.getProperty("java.io.tmpdir")), name);
        Files.createDirectories(picture.getParent());
        UiThread.call(() -> {
            Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
            shell.setSize(1200, 800);
            shell.layout(true, true);
            Display display = shell.getDisplay();
            long until = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < until) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
            Rectangle bounds = shell.getBounds();
            Image image = new Image(display, bounds.width, bounds.height);
            GC gc = new GC(display);
            gc.copyArea(image, bounds.x, bounds.y);
            gc.dispose();
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { image.getImageData() };
            loader.save(picture.toString(), SWT.IMAGE_PNG);
            image.dispose();
            return null;
        });
        assertTrue(Files.size(picture) > 0, "a picture was saved at " + picture);
        System.out.println("jawata-ux picture " + picture);
    }

    @Test
    public void theChatOpensAsAViewFromItsCommandLoadsAndTakesACommand() throws Exception {
        Path file = Files.createTempFile("jawata-view-", ".txt");
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        try {
            run(ChatCommandHandler.OPEN_CHAT);
            ChatView view = UiThread.call(() -> (ChatView) page.findView(ChatView.ID));
            assertNotNull(view, "the chat view is shown");
            Browser browser = browserOf(view.page());
            awaitChat(browser, "view");
            awaitAttach(browser, file);
            picture("chat-view.png");
        } finally {
            Files.deleteIfExists(file);
            UiThread.call(() -> {
                page.hideView(page.findViewReference(ChatView.ID));
                return null;
            });
        }
    }

    @Test
    public void theChatOpensInTheEditorAreaFromItsCommandLoadsAndTakesACommand() throws Exception {
        Path file = Files.createTempFile("jawata-editor-", ".txt");
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        try {
            run(ChatCommandHandler.OPEN_CHAT_IN_EDITOR);
            IEditorPart part = UiThread.call(() -> page.findEditor(new ChatEditorInput()));
            assertTrue(part instanceof ChatEditor, "the chat is a page in the editor area");
            ChatEditor editor = (ChatEditor) part;
            assertNotNull(editor.host(), "the editor has its host");
            Browser browser = browserOf(editor.page());
            awaitChat(browser, "editor area");
            awaitAttach(browser, file);
            assertFalse(UiThread.call(editor::isDirty), "the chat is never a document to save");
            System.out.println("jawata-ux editor area visible=" + UiThread.call(page::isEditorAreaVisible) + " active=" + UiThread.call(() -> page.getActiveEditor())
                + " refs=" + UiThread.call(() -> Arrays.stream(page.getEditorReferences()).map(reference -> reference.getId()).toList()));
            picture("chat-editor.png");
        } finally {
            Files.deleteIfExists(file);
            UiThread.call(() -> {
                page.closeEditor(page.findEditor(new ChatEditorInput()), false);
                return null;
            });
        }
    }

    @Test
    public void theEditorPlacementHasAMenuEntryInTheWindowMenu() {
        long entries = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus"))
            .filter(contribution -> contribution.getAttribute("locationURI") != null && contribution.getAttribute("locationURI").startsWith("menu:window"))
            .flatMap(contribution -> Arrays.stream(contribution.getChildren("command")))
            .filter(command -> ChatCommandHandler.commandId(ChatCommandHandler.OPEN_CHAT_IN_EDITOR).equals(command.getAttribute("commandId")))
            .filter(command -> command.getAttribute("label") != null && !command.getAttribute("label").isBlank()
                && command.getAttribute("tooltip") != null && !command.getAttribute("tooltip").isBlank())
            .count();
        assertEquals(1, entries, "one labelled entry with a mouseover opens the chat in the editor area from the Window menu");
    }

    @Test
    public void openingTheEditorTwiceShowsTheOnePageAndTheOpenCommandDoesNotAlsoOpenTheView() throws Exception {
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        try {
            run(ChatCommandHandler.OPEN_CHAT_IN_EDITOR);
            IEditorPart first = UiThread.call(() -> page.findEditor(new ChatEditorInput()));
            run(ChatCommandHandler.OPEN_CHAT_IN_EDITOR);
            IEditorPart second = UiThread.call(() -> page.findEditor(new ChatEditorInput()));
            assertSame(first, second, "the second opening shows the same editor");
            long chats = UiThread.call(() -> Arrays.stream(page.getEditorReferences()).filter(reference -> ChatEditor.ID.equals(reference.getId())).count());
            assertEquals(1, chats, "one chat editor, not two conversations");
            run(ChatCommandHandler.OPEN_CHAT);
            assertNull(UiThread.call(() -> page.findView(ChatView.ID)), "with the chat open in the editor area, the open command does not also open the view");
        } finally {
            UiThread.call(() -> {
                page.closeEditor(page.findEditor(new ChatEditorInput()), false);
                return null;
            });
        }
    }
}
