package org.jawata.eclipse.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExecutableExtension;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Runs one Eclipse command of the chat (plan Stage 21, step 21.7; ledger 28i-C56-3). plugin.xml registers this class once per command,
 * with the command's name as the data after the colon ({@code class="...ChatCommandHandler:sendPrompt"}), so one class serves them all.
 * It shows the chat, then sends the command and its arguments into the page, whose table of commands does what the page's own intent does;
 * the stream is therefore the same whichever way the person started it. A command that needs a value the caller did not give asks the person
 * for it (a mode, a model, a file) and does nothing when the person cancels.
 */
public final class ChatCommandHandler extends AbstractHandler implements IExecutableExtension {

    /** The prefix of every command and parameter id plugin.xml declares for the chat. */
    public static final String ID_PREFIX = "org.jawata.eclipse.chat.";

    /** The commands the page runs by name, with the names of their arguments; plugin.xml declares exactly these, besides the two below. */
    public static final Map<String, List<String>> PAGE_COMMANDS = pageCommands();

    /** The command that only shows the chat. */
    public static final String OPEN_CHAT = "openChat";

    /** The command that opens the chat as a page in the editor area (plan Stage 23; one of the two placements the person picks from). */
    public static final String OPEN_CHAT_IN_EDITOR = "openChatInEditor";

    /** The command that opens the chat's preference page. */
    public static final String CONFIGURE_AGENT = "configureAgent";

    private String name = OPEN_CHAT;

    private static Map<String, List<String>> pageCommands() {
        Map<String, List<String>> commands = new LinkedHashMap<>();
        commands.put("connectAgent", List.of());
        commands.put("newConversation", List.of());
        commands.put("disconnectAgent", List.of());
        commands.put("restartAgent", List.of());
        commands.put("openSession", List.of("sessionId"));
        commands.put("sendPrompt", List.of("text"));
        commands.put("cancelTurn", List.of());
        commands.put("setMode", List.of("modeId"));
        commands.put("setModel", List.of("modelId"));
        commands.put("attachFile", List.of("path"));
        return Map.copyOf(commands);
    }

    /** The id of the Eclipse command that runs the chat command with this name. */
    public static String commandId(String name) {
        return ID_PREFIX + name;
    }

    /** The id of the parameter that carries the named argument of the named command. */
    public static String parameterId(String command, String argument) {
        return ID_PREFIX + command + "." + argument;
    }

    @Override
    public void setInitializationData(IConfigurationElement config, String propertyName, Object data) throws CoreException {
        if (data instanceof String given && !given.isBlank()) {
            name = given;
        }
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        Map<String, String> args = new LinkedHashMap<>();
        for (String argument : PAGE_COMMANDS.getOrDefault(name, List.of())) {
            String given = event.getParameter(parameterId(name, argument));
            if (given != null) {
                args.put(argument, given);
            }
        }
        if (CONFIGURE_AGENT.equals(name)) {
            PreferencesUtil.createPreferenceDialogOn(window.getShell(), ChatPreferencePage.ID, null, null).open();
            return null;
        }
        if (OPEN_CHAT_IN_EDITOR.equals(name)) {
            try {
                // one chat per window: the editor takes the view's place, so no second conversation runs beside the first
                IViewReference open = window.getActivePage().findViewReference(ChatView.ID);
                if (open != null) {
                    window.getActivePage().hideView(open);
                }
                window.getActivePage().openEditor(new ChatEditorInput(), ChatEditor.ID);
            } catch (PartInitException failed) {
                throw new ExecutionException("The chat cannot be shown in the editor area: " + failed.getMessage(), failed);
            }
            return null;
        }
        ChatHost host = show(window.getActivePage());
        if (host == null || OPEN_CHAT.equals(name)) {
            return null;
        }
        if (!askForMissing(window.getShell(), args)) {
            return null;
        }
        host.command(name, args);
        return null;
    }

    /** Shows the chat and gives its host: the one in the editor area when the person has the chat open there, else the view's. Null when it cannot be shown. */
    private static ChatHost show(IWorkbenchPage page) throws ExecutionException {
        try {
            if (page.findEditor(new ChatEditorInput()) instanceof ChatEditor editor) {
                page.activate(editor);
                return editor.host();
            }
            return page.showView(ChatView.ID) instanceof ChatView view ? view.host() : null;
        } catch (PartInitException failed) {
            throw new ExecutionException("The chat cannot be shown: " + failed.getMessage(), failed);
        }
    }

    /**
     * Asks the person for the one value this command cannot do without.
     *
     * @return false when the person cancelled
     */
    private boolean askForMissing(Shell shell, Map<String, String> args) {
        switch (name) {
            case "setMode" -> {
                return askText(shell, args, "modeId", "Mode", "The id of the mode the agent should work in:");
            }
            case "setModel" -> {
                return askText(shell, args, "modelId", "Model", "The id of the model the agent should use:");
            }
            case "attachFile" -> {
                if (!args.containsKey("path")) {
                    String picked = new FileDialog(shell, SWT.OPEN).open();
                    if (picked == null) {
                        return false;
                    }
                    args.put("path", picked);
                }
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    private static boolean askText(Shell shell, Map<String, String> args, String argument, String title, String question) {
        if (args.containsKey(argument)) {
            return true;
        }
        InputDialog dialog = new InputDialog(shell, title, question, "", null);
        if (dialog.open() != Window.OK || dialog.getValue().isBlank()) {
            return false;
        }
        args.put(argument, dialog.getValue().trim());
        return true;
    }
}
