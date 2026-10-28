package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IParameter;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.jawata.eclipse.chat.ChatCommandHandler;
import org.jawata.eclipse.chat.ChatView;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, step 21.7 (ledger 28i-C56-3): the chat has one Eclipse command for each thing the person starts on it, with the arguments
 * as parameters and a key for opening it. Four things are pinned: every command is defined, handled and carries its parameters; the page's
 * table of commands is the same list; a conversation driven by commands gives the view the stream the page's own intent gives (chat-core's
 * golden for R1); and the real handler, run on the real view, reaches the real page.
 */
public class ChatCommandsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every command the chat declares: the ones the page runs, and the two Java answers itself. */
    private static List<String> declared() {
        List<String> names = new ArrayList<>(ChatCommandHandler.PAGE_COMMANDS.keySet());
        names.add(ChatCommandHandler.OPEN_CHAT);
        names.add(ChatCommandHandler.OPEN_CHAT_IN_EDITOR);
        names.add(ChatCommandHandler.CONFIGURE_AGENT);
        return names;
    }

    @Test
    public void everyCommandIsDefinedWithItsParametersAndHandledByTheChat() throws Exception {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        IConfigurationElement[] handlers = Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.handlers");
        for (String name : declared()) {
            String id = ChatCommandHandler.commandId(name);
            Command command = commands.getCommand(id);
            assertTrue(command.isDefined(), "the command " + id + " is defined");
            List<String> wanted = new ArrayList<>();
            ChatCommandHandler.PAGE_COMMANDS.getOrDefault(name, List.of()).forEach(argument -> wanted.add(ChatCommandHandler.parameterId(name, argument)));
            List<String> declaredParameters = new ArrayList<>();
            IParameter[] parameters = command.getParameters();
            for (IParameter parameter : parameters == null ? new IParameter[0] : parameters) {
                declaredParameters.add(parameter.getId());
                assertTrue(parameter.isOptional(), "the parameter " + parameter.getId() + " is optional: the command also works without it");
            }
            assertEquals(wanted, declaredParameters, "the parameters of " + id);
            long handled = Arrays.stream(handlers)
                .filter(handler -> id.equals(handler.getAttribute("commandId")))
                .filter(handler -> ("org.jawata.eclipse.chat.ChatCommandHandler:" + name).equals(handler.getAttribute("class")))
                .count();
            assertEquals(1, handled, "one handler for " + id);
        }
        long chatCommands = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.commands"))
            .filter(element -> "command".equals(element.getName()) && element.getAttribute("id").startsWith(ChatCommandHandler.ID_PREFIX)).count();
        assertEquals(declared().size(), chatCommands, "plugin.xml defines no chat command the handler does not know");
    }

    @Test
    public void openingTheChatHasAKey() {
        long keys = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.bindings"))
            .filter(key -> ChatCommandHandler.commandId(ChatCommandHandler.OPEN_CHAT).equals(key.getAttribute("commandId")))
            .filter(key -> key.getAttribute("sequence") != null && !key.getAttribute("sequence").isBlank()).count();
        assertEquals(1, keys, "one key opens the chat");
    }

    @Test
    public void thePagesCommandsAreTheCommandsJavaDeclares() throws Exception {
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent())) {
            page.run("window.commandNames();");
            String known = page.waitForCall("asked").path("value").asText();
            assertEquals(new TreeSet<>(ChatCommandHandler.PAGE_COMMANDS.keySet()), new TreeSet<>(List.of(known.split(","))),
                "the page runs exactly the commands Java sends it");
        }
    }

    @Test
    public void aConversationDrivenByCommandsTellsTheViewWhatChatCoreSaysForR1() throws Exception {
        FixedChatSettings settings = new FixedChatSettings(ChatCheckout.replayCommand("R1"), Map.of(), FixedChatSettings.withoutAgent().workingFolder());
        try (HostTestPage page = new HostTestPage(settings)) {
            page.run("window.playByCommands();");
            page.command("connectAgent", Map.of());
            page.command("sendPrompt", Map.of("text", "replay"));
            JsonNode actual = page.waitForCall("played").path("body");
            JsonNode golden = JSON.readTree(ChatCheckout.golden("R1").toFile());
            assertTrue(actual.path("messages").size() > 0, "the page was told something");
            assertEquals(golden, actual, "the view stream by commands equals the one the page's own intent gives");
        }
    }

    @Test
    public void theRealHandlerRunOnTheRealViewReachesTheRealPage() throws Exception {
        Path file = Files.createTempFile("jawata-attach-", ".txt");
        AtomicReference<IWorkbenchPage> shown = new AtomicReference<>();
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
            assertNotNull(view.host(), "the view has its host");
            Browser browser = UiThread.call(() -> (Browser) view.page().getChildren()[0]);
            Await.found("the chat in the view's page", () -> Optional.ofNullable(UiThread.call(() ->
                Boolean.TRUE.equals(browser.evaluate("return typeof window.jawataChatPage === 'object';")) ? Boolean.TRUE : null)));
            UiThread.call(() -> browser.evaluate("window.__seen = []; window.jawataChatPage.link.page.onMessage((m) => window.__seen.push(m.type)); return true;"));

            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
            Command attach = commands.getCommand(ChatCommandHandler.commandId("attachFile"));
            ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(attach,
                Map.of(ChatCommandHandler.parameterId("attachFile", "path"), file.toString()));
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
        } finally {
            Files.deleteIfExists(file);
            IWorkbenchPage page = shown.get();
            if (page != null) {
                Display.getDefault().syncExec(() -> page.hideView(page.findViewReference(ChatView.ID)));
            }
        }
    }
}
