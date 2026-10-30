package org.jawata.eclipse.commit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;

/**
 * The commit message draft, for the person to read and copy (plan Stage 22b, spec D13, the fallback route its first check chose). EGit gives
 * no dependable way to fill its commit box, so the draft is shown here: the agent writes it with its ordinary file tools to
 * {@code jawata-commit-draft.txt} inside the repository's {@code .git} folder, this view shows it, and the person copies it into EGit's
 * commit box and commits there. A draft that carries AI attribution is not offered; the reason is shown instead. Nothing here commits,
 * pushes or touches the repository.
 */
public final class CommitDraftView extends ViewPart {

    /** The id plugin.xml gives the view. */
    public static final String ID = "org.jawata.eclipse.commit.view";

    static final String DRAFT_FILE = "jawata-commit-draft.txt";
    private static final int POLL_MILLIS = 500;

    private Label where;
    private Text text;
    private Button copy;
    private Button ask;
    private String askNote = "";
    private String offered = "";
    private String lastRead = "";
    private CommitDraftCheck check;

    @Override
    public void createPartControl(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(1, false));
        where = new Label(area, SWT.NONE);
        where.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        text = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite buttons = new Composite(area, SWT.NONE);
        buttons.setLayout(new GridLayout(2, false));
        buttons.setLayoutData(new GridData(SWT.LEFT, SWT.BOTTOM, false, false));
        ask = new Button(buttons, SWT.PUSH);
        ask.setText("Ask the agent");
        ask.setToolTipText("Asks the agent in the jawata chat to write a commit message for your changes. Nothing is committed: the draft appears here for you to copy.");
        ask.addListener(SWT.Selection, event -> askTheAgent());
        copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy");
        copy.setToolTipText("Copies the draft; paste it into the commit message box of the Git Staging view, then commit there.");
        copy.setLayoutData(new GridData(SWT.LEFT, SWT.BOTTOM, false, false));
        copy.addListener(SWT.Selection, event -> copyDraft());
        try {
            check = CommitDraftCheck.fromBundle();
        } catch (IOException unreadable) {
            check = null;
            lastRead = "The list of refused commit-message cases could not be read, so no draft is offered: " + unreadable.getMessage();
        }
        refresh();
        parent.getDisplay().timerExec(POLL_MILLIS, this::tick);
    }

    @Override
    public void setFocus() {
        if (text != null && !text.isDisposed()) {
            text.setFocus();
        }
    }

    private void tick() {
        if (text != null && !text.isDisposed()) {
            refresh();
            text.getDisplay().timerExec(POLL_MILLIS, this::tick);
        }
    }

    /** Reads the newest draft of the open projects' repositories and shows it, or the reason it is refused, or how to ask for one. */
    public void refresh() {
        Optional<Path> found = newestDraft();
        String shown;
        String title;
        String accepted = "";
        if (check == null) {
            title = "Commit draft";
            shown = lastRead;
        } else if (found.isPresent()) {
            Path file = found.get();
            title = "Draft for " + file.getParent().getParent().getFileName();
            try {
                String draft = Files.readString(file, StandardCharsets.UTF_8);
                Optional<String> refusal = check.refusal(draft);
                shown = refusal.orElse(draft);
                accepted = refusal.isPresent() ? "" : draft;
            } catch (IOException unreadable) {
                shown = "The draft could not be read: " + unreadable.getMessage();
            }
        } else {
            Optional<Path> git = firstGitDirectory();
            title = "No draft yet";
            shown = git.isPresent()
                ? "Ask the agent in the chat:\n\n" + request(git.get())
                : "None of the open projects is in a git repository.";
        }
        if (!askNote.isEmpty()) {
            shown = shown + "\n\n" + askNote;
        }
        offered = accepted;
        ask.setEnabled(agentCanBeAsked());
        if (!where.getText().equals(title)) {
            where.setText(title);
        }
        if (!text.getText().equals(shown)) {
            text.setText(shown);
        }
        copy.setEnabled(!accepted.isEmpty());
    }

    /**
     * What the view shows now.
     *
     * @return the draft, the reason it is refused, or the words about asking for one
     */
    public String shown() {
        return text.getText();
    }

    /**
     * Whether the Copy button can be pressed: there is a draft that is not refused.
     *
     * @return true when a draft is offered
     */
    public boolean copyEnabled() {
        return copy.isEnabled();
    }

    /** The chat's command that sends a text to the agent; the view names it by id, so the host bundle does not depend on the chat bundle. */
    static final String ASK_COMMAND = "org.jawata.eclipse.chat.sendPrompt";

    /** The parameter of {@link #ASK_COMMAND} that carries the text. */
    static final String ASK_TEXT = "org.jawata.eclipse.chat.sendPrompt.text";

    /**
     * The sentence that asks the agent for a commit message.
     *
     * @param git a repository's {@code .git} folder
     * @return the request, naming the draft file the agent writes
     */
    public static String request(Path git) {
        return "Write a commit message for my changes into " + git.resolve(DRAFT_FILE) + ". Do not add a Co-Authored-By line or any other mention of an AI.";
    }

    /** The repository the request is about: the one holding the newest draft, else the first open project's. */
    private static Optional<Path> askedRepository() {
        return newestDraft().map(Path::getParent).or(CommitDraftView::firstGitDirectory);
    }

    /** True when there is a repository to ask about and the chat's command exists, so pressing the button can reach the agent. */
    private static boolean agentCanBeAsked() {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        return commands != null && commands.getCommand(ASK_COMMAND).isDefined() && askedRepository().isPresent();
    }

    /**
     * What the Ask the agent button does: sends the request for a commit message to the chat, through the chat's command, as if the person
     * had typed it there. When the chat cannot be reached the reason is shown under the draft. Nothing is committed.
     */
    public void askTheAgent() {
        Optional<Path> git = askedRepository();
        if (git.isPresent()) {
            try {
                ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
                IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
                handlers.executeCommand(ParameterizedCommand.generateCommand(commands.getCommand(ASK_COMMAND), Map.of(ASK_TEXT, request(git.get()))), null);
                askNote = "";
            } catch (ExecutionException | NotDefinedException | NotEnabledException | NotHandledException failed) {
                askNote = "The chat could not be asked: " + failed.getMessage();
            }
            refresh();
        }
    }

    /**
     * Whether the Ask the agent button can be pressed: there is a repository to ask about and the chat's command exists.
     *
     * @return true when pressing the button can reach the agent
     */
    public boolean askEnabled() {
        return ask.isEnabled();
    }

    private void copyDraft() {
        if (!offered.isEmpty()) {
            Clipboard clipboard = new Clipboard(copy.getDisplay());
            try {
                clipboard.setContents(new Object[] { offered }, new Transfer[] { TextTransfer.getInstance() });
            } finally {
                clipboard.dispose();
            }
        }
    }

    private static Optional<Path> newestDraft() {
        Path best = null;
        FileTime bestTime = null;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            Optional<Path> git = gitDirectoryOf(project);
            if (git.isPresent()) {
                Path file = git.get().resolve(DRAFT_FILE);
                if (Files.isRegularFile(file)) {
                    try {
                        FileTime time = Files.getLastModifiedTime(file);
                        if (bestTime == null || time.compareTo(bestTime) > 0) {
                            best = file;
                            bestTime = time;
                        }
                    } catch (IOException unreadable) {
                        // a draft whose time cannot be read is not offered
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Path> firstGitDirectory() {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            Optional<Path> git = gitDirectoryOf(project);
            if (git.isPresent()) {
                return git;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> gitDirectoryOf(IProject project) {
        if (!project.isOpen() || project.getLocation() == null) {
            return Optional.empty();
        }
        for (Path folder = project.getLocation().toFile().toPath(); folder != null; folder = folder.getParent()) {
            Path git = folder.resolve(".git");
            if (Files.isDirectory(git)) {
                return Optional.of(git);
            }
        }
        return Optional.empty();
    }
}
