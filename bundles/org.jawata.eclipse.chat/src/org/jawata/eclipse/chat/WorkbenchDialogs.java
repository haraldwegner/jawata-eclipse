package org.jawata.eclipse.chat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.statushandlers.StatusManager;
import org.osgi.framework.FrameworkUtil;

/**
 * The chat's questions and notices as Eclipse shows such things (plan Stage 21, step 21.9): a question is a message dialog, opened on
 * the user interface thread after the page's call has returned; the agent's setup is the preference dialog on the chat's page; work in
 * progress is a background job in Eclipse's progress area; a notice is a line in the view's status line; an error goes to Eclipse's
 * error handling, which shows and logs it.
 */
public final class WorkbenchDialogs implements ChatDialogs {

    /** The title of every dialog the chat opens. */
    public static final String TITLE = "jawata chat";
    /** The label of the way out of every question. */
    public static final String CANCEL = "Cancel";

    private final Supplier<Shell> shell;
    private final Consumer<String> statusLine;
    private final Map<String, CountDownLatch> work = new ConcurrentHashMap<>();

    /**
     * @param shell the window the dialogs belong to
     * @param statusLine shows one line to the person; called on the user interface thread
     */
    public WorkbenchDialogs(Supplier<Shell> shell, Consumer<String> statusLine) {
        this.shell = shell;
        this.statusLine = statusLine;
    }

    private static void onUserInterface(Runnable job) {
        Display.getDefault().asyncExec(job);
    }

    @Override
    public void confirm(String question, String yes, Consumer<Boolean> answer) {
        onUserInterface(() -> {
            MessageDialog dialog = new MessageDialog(shell.get(), TITLE, null, question, MessageDialog.QUESTION, new String[] {yes, CANCEL}, 0);
            answer.accept(dialog.open() == 0);
        });
    }

    @Override
    public void choose(String agentName, List<Method> methods, Consumer<Optional<String>> answer) {
        onUserInterface(() -> {
            String[] buttons = new String[methods.size() + 1];
            for (int i = 0; i < methods.size(); i++) {
                buttons[i] = methods.get(i).name();
            }
            buttons[methods.size()] = CANCEL;
            String text = "How should " + agentName + " sign you in?";
            int picked = new MessageDialog(shell.get(), TITLE, null, text, MessageDialog.QUESTION, buttons, 0).open();
            answer.accept(picked >= 0 && picked < methods.size() ? Optional.of(methods.get(picked).id()) : Optional.empty());
        });
    }

    @Override
    public void configureAgent(Runnable done) {
        onUserInterface(() -> {
            PreferencesUtil.createPreferenceDialogOn(shell.get(), ChatPreferencePage.ID, new String[] {ChatPreferencePage.ID}, null).open();
            done.run();
        });
    }

    @Override
    public void progressBegun(String ticket, String title) {
        CountDownLatch finished = new CountDownLatch(1);
        work.put(ticket, finished);
        Job job = new Job(title) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    finished.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    @Override
    public void progressEnded(String ticket) {
        CountDownLatch finished = work.remove(ticket);
        if (finished != null) {
            finished.countDown();
        }
    }

    @Override
    public void info(String message) {
        onUserInterface(() -> statusLine.accept(message));
    }

    @Override
    public void error(String message) {
        String plugin = FrameworkUtil.getBundle(WorkbenchDialogs.class).getSymbolicName();
        StatusManager.getManager().handle(new Status(IStatus.ERROR, plugin, message), StatusManager.SHOW | StatusManager.LOG);
    }
}
