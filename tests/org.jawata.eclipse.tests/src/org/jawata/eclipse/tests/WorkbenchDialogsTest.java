package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.jawata.eclipse.chat.ChatDialogs;
import org.jawata.eclipse.chat.ChatPreferencePage;
import org.jawata.eclipse.chat.WorkbenchDialogs;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 21, step 21.9 (ledger 28i-C13-10): the chat's questions and notices, shown by Eclipse. The real dialogs are opened and the
 * person's buttons are pressed: a yes gives true, Cancel gives false, a choice gives the one picked, work in progress shows in Eclipse's
 * progress area while it runs, and a notice reaches the status line. The page's settings dialog opens on the chat's page.
 */
public class WorkbenchDialogsTest {

    private final List<String> statusLine = new CopyOnWriteArrayList<>();
    private final WorkbenchDialogs dialogs = new WorkbenchDialogs(() -> Display.getDefault().getActiveShell(), statusLine::add);

    /** The dialog the chat opened, once it is showing. */
    private static Shell waitForDialog() throws InterruptedException {
        return Await.found("the chat's dialog", () -> {
            AtomicReference<Optional<Shell>> found = new AtomicReference<>(Optional.empty());
            Display.getDefault().syncExec(() -> {
                for (Shell shell : Display.getDefault().getShells()) {
                    if (WorkbenchDialogs.TITLE.equals(shell.getText()) && shell.isVisible()) {
                        found.set(Optional.of(shell));
                    }
                }
            });
            return found.get();
        });
    }

    private static Optional<Button> buttonLabelled(Control control, String label) {
        if (control instanceof Button button && label.equals(button.getText().replace("&", ""))) {
            return Optional.of(button);
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                Optional<Button> found = buttonLabelled(child, label);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** The person presses the button with this label in the chat's dialog. */
    private static void press(String label) throws InterruptedException {
        Shell dialog = waitForDialog();
        Display.getDefault().syncExec(() -> {
            Button button = buttonLabelled(dialog, label).orElseThrow(() -> new AssertionError("no button \"" + label + "\" in the dialog"));
            button.notifyListeners(SWT.Selection, new Event());
        });
    }

    @Test
    public void aQuestionIsAnsweredYesByItsActionAndNoByCancel() throws Exception {
        AtomicReference<Boolean> first = new AtomicReference<>();
        dialogs.confirm("Start a new conversation? The messages now shown are cleared.", "New Conversation", first::set);
        press("New Conversation");
        Await.found("the answer", () -> Optional.ofNullable(first.get()));
        assertEquals(true, first.get());

        AtomicReference<Boolean> second = new AtomicReference<>();
        dialogs.confirm("Restart the agent?", "Restart", second::set);
        press(WorkbenchDialogs.CANCEL);
        Await.found("the answer", () -> Optional.ofNullable(second.get()));
        assertEquals(false, second.get());
    }

    @Test
    public void aSignInChoiceGivesTheMethodPickedOrNothingOnCancel() throws Exception {
        List<ChatDialogs.Method> methods = List.of(new ChatDialogs.Method("login", "Log in with a browser", ""), new ChatDialogs.Method("key", "Use an API key", ""));
        AtomicReference<Optional<String>> picked = new AtomicReference<>();
        dialogs.choose("Claude", methods, picked::set);
        press("Use an API key");
        Await.found("the choice", () -> Optional.ofNullable(picked.get()));
        assertEquals(Optional.of("key"), picked.get());

        AtomicReference<Optional<String>> cancelled = new AtomicReference<>();
        dialogs.choose("Claude", methods, cancelled::set);
        press(WorkbenchDialogs.CANCEL);
        Await.found("the choice", () -> Optional.ofNullable(cancelled.get()));
        assertEquals(Optional.empty(), cancelled.get());
    }

    private static Optional<Job> jobNamed(String name) {
        for (Job job : Job.getJobManager().find(null)) {
            if (name.equals(job.getName())) {
                return Optional.of(job);
            }
        }
        return Optional.empty();
    }

    @Test
    public void workInProgressShowsInEclipsesProgressAreaUntilItEnds() throws Exception {
        dialogs.progressBegun("t1", "Connecting to the agent…");
        Job job = Await.found("the job in Eclipse's progress area", () -> jobNamed("Connecting to the agent…"));
        assertTrue(!job.isSystem() && !job.isUser(), "background work the person sees but did not start");

        dialogs.progressEnded("t1");
        assertTrue(Await.until(() -> job.getState() == Job.NONE), "and it leaves the area when the work ends");
    }

    @Test
    public void aNoticeReachesTheStatusLine() throws Exception {
        dialogs.info("Restarted Claude.");
        Await.found("the notice", () -> statusLine.isEmpty() ? Optional.empty() : Optional.of(statusLine.get(0)));
        assertEquals(List.of("Restarted Claude."), statusLine);
    }

    @Test
    public void theSettingsDialogOpensOnTheChatsPage() {
        AtomicReference<PreferenceDialog> dialog = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            Shell parent = new Shell(Display.getDefault());
            PreferenceDialog opened = PreferencesUtil.createPreferenceDialogOn(parent, ChatPreferencePage.ID, new String[] {ChatPreferencePage.ID}, null);
            opened.setBlockOnOpen(false);
            opened.open();
            dialog.set(opened);
        });
        try {
            Object page = onThread(() -> dialog.get().getSelectedPage());
            assertNotNull(page, "the page registered in plugin.xml opened");
            assertInstanceOf(ChatPreferencePage.class, page);
        } finally {
            Display.getDefault().syncExec(() -> {
                Shell parent = (Shell) dialog.get().getShell().getParent();
                dialog.get().close();
                parent.dispose();
            });
        }
    }

    private static Object onThread(Supplier<Object> read) {
        AtomicReference<Object> value = new AtomicReference<>();
        Display.getDefault().syncExec(() -> value.set(read.get()));
        return value.get();
    }
}
