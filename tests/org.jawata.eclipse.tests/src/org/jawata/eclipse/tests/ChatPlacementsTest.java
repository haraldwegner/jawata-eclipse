package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.intro.IIntroManager;
import org.jawata.eclipse.chat.ChatCommandHandler;
import org.jawata.eclipse.chat.ChatPage;
import org.jawata.eclipse.chat.ChatPlacementChoice;
import org.jawata.eclipse.chat.ChatPlacementChoice.Where;
import org.jawata.eclipse.chat.ChatView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 23 (spec D10, his pick of 2026-10-28): the chat is one view, and a chat that opens goes where the person chose, in the editor
 * area in the middle or in the side panel. The first time the chat opens it asks once and keeps the answer. A chat the person closes opens
 * again where they chose; one that is open stays where it is. The picture of each place is saved where the ux seat reads it
 * ({@code jawata.ux.dir}, else the temp folder).
 */
public class ChatPlacementsTest {

    /** The mark the view keeps in the workbench's saved layout once placed (the same text as the view's own constant, which is not visible here). */
    private static final String PLACED = "org.jawata.eclipse.chat.placed";

    private Runnable putBack = () -> { };

    @AfterEach
    public void leaveThePersonsChoiceAsItWas() {
        putBack.run();
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        UiThread.call(() -> {
            if (page.findViewReference(ChatView.ID) != null) {
                page.hideView(page.findViewReference(ChatView.ID));
            }
            return null;
        });
    }

    /** The page the chat opens on, with Eclipse's Welcome page closed: it fills the window in the test workbench and hides the editor area. */
    private static IWorkbenchPage activePage() {
        IIntroManager intro = PlatformUI.getWorkbench().getIntroManager();
        if (intro.getIntro() != null) {
            intro.closeIntro(intro.getIntro());
        }
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    /** Makes a part that is still there the active one: after the chat is closed, Eclipse still resolves commands from the chat's disposed part. */
    private static void activateSomethingThatStays() {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IViewPart explorer = page.findView("org.eclipse.ui.navigator.ProjectExplorer");
        if (explorer != null) {
            page.activate(explorer);
        }
    }

    private static void run(String name) {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        // the window's own service: the workbench-level one answered NotHandledException once an earlier test had run in this headless run, where no window is active
        IHandlerService handlers = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getService(IHandlerService.class);
        Command command = commands.getCommand(ChatCommandHandler.commandId(name));
        UiThread.call(() -> {
            try {
                activateSomethingThatStays();
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
        // the window's own service: the workbench-level one answered NotHandledException once an earlier test had run in this headless run, where no window is active
        IHandlerService handlers = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getService(IHandlerService.class);
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

    private static MPart partOf(ChatView view) {
        return view.getSite().getService(MPart.class);
    }

    /** Whether the chat view sits in the editor area, found by walking up from the placeholder that stands for it in the perspective. */
    private static boolean inTheEditorArea(ChatView view) {
        MUIElement up = partOf(view).getCurSharedRef();
        while (up != null && !(up instanceof MArea)) {
            up = up.getParent();
        }
        return up != null;
    }

    /** What the workbench model holds for the chat view, for the message of a test that finds no view. */
    private static String modelEntriesOfTheChat() {
        return UiThread.call(() -> {
            EModelService models = PlatformUI.getWorkbench().getService(EModelService.class);
            MApplication application = PlatformUI.getWorkbench().getService(MApplication.class);
            StringBuilder out = new StringBuilder();
            for (MUIElement entry : models.findElements(application, ChatView.ID, MUIElement.class)) {
                out.append("[").append(entry.getClass().getSimpleName()).append(" toBeRendered=").append(entry.isToBeRendered()).append(" parent=")
                    .append(entry.getParent() == null ? null : entry.getParent().getClass().getSimpleName()).append(" tags=").append(entry.getTags()).append("]");
            }
            return out.toString();
        });
    }

    private static ChatView open(IWorkbenchPage page) {
        run(ChatCommandHandler.OPEN_CHAT);
        ChatView view = UiThread.call(() -> (ChatView) page.findView(ChatView.ID));
        assertNotNull(view, () -> "the chat view is shown; the page holds the views " + UiThread.call(() -> java.util.Arrays.stream(page.getViewReferences()).map(r -> r.getId()).toList())
            + " in perspective " + UiThread.call(() -> page.getPerspective().getId()) + ", and it is the active page: "
            + UiThread.call(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage() == page) + "; the model holds " + modelEntriesOfTheChat());
        return view;
    }

    private static void awaitInTheEditorArea(ChatView view) throws Exception {
        Await.found("the chat moved into the editor area", () -> Optional.ofNullable(UiThread.call(() -> inTheEditorArea(view) ? Boolean.TRUE : null)));
    }

    @Test
    public void aChatOpenedWithTheMiddleChosenSitsInTheEditorAreaAsAViewAndTakesACommand() throws Exception {
        putBack = ChatPlacementSetup.choose(Where.EDITOR_AREA);
        Path file = Files.createTempFile("jawata-middle-", ".txt");
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        try {
            ChatView view = open(page);
            awaitInTheEditorArea(view);
            assertNotNull(UiThread.call(() -> page.findView(ChatView.ID)), "it is still a view: Eclipse finds it by its id");
            Browser browser = browserOf(view.page());
            awaitChat(browser, "editor area");
            awaitAttach(browser, file);
            assertEquals("yes", UiThread.call(() -> partOf(view).getPersistedState().get(PLACED)), "the saved layout carries the mark of a placed chat");
            picture("chat-in-the-middle.png");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void aChatOpenedWithTheSidePanelChosenStaysOutOfTheEditorAreaAndTakesACommand() throws Exception {
        putBack = ChatPlacementSetup.choose(Where.SIDE_PANEL);
        Path file = Files.createTempFile("jawata-side-", ".txt");
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        try {
            ChatView view = open(page);
            Browser browser = browserOf(view.page());
            awaitChat(browser, "side panel");
            awaitAttach(browser, file);
            // Eclipse opens a view where it was last, so a chat an earlier test left in the middle must be sent out to the side panel
            Await.found("the chat outside the editor area", () -> UiThread.call(() -> inTheEditorArea(view)) ? Optional.empty() : Optional.of(true));
            picture("chat-in-a-side-panel.png");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void theFirstTimeTheChatOpensItAsksOnceAndKeepsTheAnswer() throws Exception {
        putBack = ChatPlacementSetup.choose(Where.SIDE_PANEL);
        ChatPlacementChoice.askAgain();
        AtomicInteger asked = new AtomicInteger();
        Runnable questionBack = ChatPlacementChoice.askWith(shell -> {
            asked.incrementAndGet();
            return Optional.of(Where.EDITOR_AREA);
        });
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        try {
            ChatView first = open(page);
            awaitInTheEditorArea(first);
            assertEquals(1, asked.get(), "the person was asked once");
            assertEquals(Optional.of(Where.EDITOR_AREA), ChatPlacementChoice.chosen(), "and the answer is kept");
            UiThread.call(() -> {
                page.hideView(page.findViewReference(ChatView.ID));
                return null;
            });
            ChatView second = open(page);
            awaitInTheEditorArea(second);
            assertEquals(1, asked.get(), "a chat that opens again does not ask again");
        } finally {
            questionBack.run();
        }
    }

    @Test
    public void aQuestionClosedWithoutAnAnswerOpensTheChatInTheMiddleAndIsAskedAgainNextTime() throws Exception {
        putBack = ChatPlacementSetup.choose(Where.SIDE_PANEL);
        ChatPlacementChoice.askAgain();
        Runnable questionBack = ChatPlacementChoice.askWith(shell -> Optional.empty());
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        try {
            ChatView view = open(page);
            awaitInTheEditorArea(view);
            assertEquals(Optional.empty(), ChatPlacementChoice.chosen(), "nothing was chosen, so the next opening asks again");
        } finally {
            questionBack.run();
        }
    }

    @Test
    public void aChatThePersonClosedOpensAgainWhereTheyChose() throws Exception {
        putBack = ChatPlacementSetup.choose(Where.EDITOR_AREA);
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        ChatView first = open(page);
        awaitInTheEditorArea(first);
        UiThread.call(() -> {
            page.hideView(page.findViewReference(ChatView.ID));
            return null;
        });
        ChatView second = open(page);
        awaitInTheEditorArea(second);
    }

    @Test
    public void aChatThatIsOpenStaysWhereItIsWhenThePreferenceChanges() throws Exception {
        putBack = ChatPlacementSetup.choose(Where.SIDE_PANEL);
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        ChatView view = open(page);
        Await.found("the chat outside the editor area", () -> UiThread.call(() -> inTheEditorArea(view)) ? Optional.empty() : Optional.of(true));
        ChatPlacementChoice.choose(Where.EDITOR_AREA);
        run(ChatCommandHandler.OPEN_CHAT);
        assertEquals(view, UiThread.call(() -> page.findView(ChatView.ID)), "the open command shows the chat that is open");
        assertFalse(UiThread.call(() -> inTheEditorArea(view)), "and does not move it");
    }

    @Test
    public void theSavedLayoutHoldsTheChatInTheMiddleWithItsMark() throws Exception {
        putBack = ChatPlacementSetup.choose(Where.EDITOR_AREA);
        IWorkbenchPage page = UiThread.call(ChatPlacementsTest::activePage);
        ChatView view = open(page);
        awaitInTheEditorArea(view);
        // the layout is saved as the workbench saves it, from a copy of the application model; the mark and the place are what a restart reads
        String saved = UiThread.call(() -> {
            try {
                MApplication application = view.getSite().getService(MApplication.class);
                XMIResourceImpl resource = new XMIResourceImpl(URI.createURI("memory:layout.xmi"));
                resource.getContents().add(EcoreUtil.copy((EObject) application));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                resource.save(out, Map.of());
                return out.toString(StandardCharsets.UTF_8);
            } catch (Exception failed) {
                throw new AssertionError("the layout cannot be written: " + failed, failed);
            }
        });
        assertTrue(saved.contains(PLACED), "the saved layout carries the placed mark");
        assertTrue(saved.contains("org.jawata.eclipse.chat.view"), "and the chat view");
    }
}
