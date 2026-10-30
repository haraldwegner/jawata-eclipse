package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ActiveShellExpression;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;
import org.jawata.eclipse.commit.CommitDraftCheck;
import org.jawata.eclipse.commit.CommitDraftView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Plan Stage 22b, spec D13 (the fallback route its first check chose): a commit message draft the agent writes with its ordinary file tools
 * shows in the plug-in's view with a Copy button, the repository's HEAD does not move, a draft with AI attribution is refused with its reason
 * by the same cases the repository's commit-msg hook refuses, and the plug-in's classes reference no commit or push operation.
 */
public class CommitDraftTest {

    private Path repo;
    private IProject project;

    @BeforeEach
    public void makeARepository() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "the git commands below are for Linux and macOS");
        repo = Files.createTempDirectory("jawata-commit-draft");
        git("init", "-q");
        git("config", "user.name", "Fixture");
        git("config", "user.email", "fixture@example.invalid");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git("add", "a.txt");
        git("commit", "-q", "-m", "first");
        Files.writeString(repo.resolve("a.txt"), "two\n");
    }

    @AfterEach
    public void removeIt() throws Exception {
        UiThread.call(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
            if (page.findView(CommitDraftView.ID) != null) {
                page.hideView(page.findView(CommitDraftView.ID));
            }
            return null;
        });
        if (project != null && project.exists()) {
            project.delete(false, true, null);
        }
        if (repo != null) {
            try (Stream<Path> walk = Files.walk(repo)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out.strip();
    }

    private String headOf() throws Exception {
        return git("rev-parse", "HEAD") + " " + git("rev-list", "--count", "HEAD");
    }

    private Path draftFile() {
        return repo.resolve(".git").resolve("jawata-commit-draft.txt");
    }

    private CommitDraftView showTheView() throws Exception {
        return UiThread.call(() -> {
            try {
                var intro = PlatformUI.getWorkbench().getIntroManager().getIntro();
                if (intro != null) {
                    PlatformUI.getWorkbench().getIntroManager().closeIntro(intro);
                }
                var description = ResourcesPlugin.getWorkspace().newProjectDescription("draft" + System.nanoTime());
                description.setLocation(new org.eclipse.core.runtime.Path(repo.toString()));
                project = ResourcesPlugin.getWorkspace().getRoot().getProject(description.getName());
                project.create(description, null);
                project.open(null);
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
                return (CommitDraftView) PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage().showView(CommitDraftView.ID);
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
        });
    }

    @Test
    public void theCheckRefusesEveryCaseOfTheHooksList() throws Exception {
        CommitDraftCheck check = CommitDraftCheck.fromBundle();
        assertTrue(check.refusal("plain subject\n\nwhat and why\n").isEmpty(), "a plain message is accepted");
        for (String refused : new String[] { "subject\n\nCo-Authored-By: Claude <noreply@anthropic.com>\n", "subject\n\nsent from noreply@anthropic.com\n",
            "subject\n\nGenerated with [Claude Code]\n", "subject\n\nco-authored-by:   ANTHROPIC\n" }) {
            assertTrue(check.refusal(refused).isPresent(), "refused like the hook refuses it: " + refused);
        }
        assertTrue(check.refusal("subject\n\nco-authored-by:\nclaude\n").isEmpty(), "a case is applied to one line at a time, as grep applies it");
    }

    @Test
    public void aDraftTheAgentWritesShowsInTheViewAndHeadDoesNotMove() throws Exception {
        String before = headOf();
        CommitDraftView view = showTheView();
        Files.writeString(draftFile(), "Show the draft in a view\n\nThe agent writes it to a file; the person copies it.\n");
        assertTrue(Await.until(() -> UiThread.call(() -> view.shown().contains("Show the draft in a view") && view.copyEnabled())),
            "the draft shows and can be copied: " + UiThread.call(view::shown));
        assertEquals(before, headOf(), "nothing was committed");
        assertEquals("two", Files.readString(repo.resolve("a.txt")).strip(), "the person's files are as they were");
    }

    @Test
    public void aDraftWithAttributionIsRefusedWithItsReason() throws Exception {
        CommitDraftView view = showTheView();
        Files.writeString(draftFile(), "Fix it\n\nCo-Authored-By: Claude <noreply@anthropic.com>\n");
        assertTrue(Await.until(() -> UiThread.call(() -> view.shown().contains("AI attribution"))), "the reason is shown: " + UiThread.call(view::shown));
        assertFalse(UiThread.call(view::copyEnabled), "a refused draft cannot be copied");
    }

    @Test
    public void withNoDraftTheViewSaysHowToAsk() throws Exception {
        CommitDraftView view = showTheView();
        assertTrue(Await.until(() -> UiThread.call(() -> view.shown().contains("Ask the agent in the chat") && view.shown().contains("jawata-commit-draft.txt"))),
            "the view says how to ask: " + UiThread.call(view::shown));
        assertFalse(UiThread.call(view::copyEnabled), "there is nothing to copy");
    }

    /** Stands in for the chat's sendPrompt command while a test runs, so no real agent is asked: it keeps the text it was sent, or fails with a reason. */
    private static final class ChatStandIn extends AbstractHandler {
        final AtomicReference<String> received = new AtomicReference<>();
        private final String failure;

        ChatStandIn(String failure) {
            this.failure = failure;
        }

        @Override
        public Object execute(ExecutionEvent event) throws org.eclipse.core.commands.ExecutionException {
            if (failure != null) {
                throw new org.eclipse.core.commands.ExecutionException(failure);
            }
            received.set(event.getParameter("org.jawata.eclipse.chat.sendPrompt.text"));
            return null;
        }
    }

    /** Puts the stand-in in the chat command's place while the workbench window is the active shell, which is more specific than the chat's own handler. */
    private IHandlerActivation standInFor(ChatStandIn handler) throws Exception {
        return UiThread.call(() -> {
            Shell window = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getShell();
            window.forceActive();
            return PlatformUI.getWorkbench().getService(IHandlerService.class)
                .activateHandler("org.jawata.eclipse.chat.sendPrompt", handler, new ActiveShellExpression(window));
        });
    }

    @Test
    public void theAskButtonSendsTheRequestThroughTheChatsCommand() throws Exception {
        CommitDraftView view = showTheView();
        // the view asks about the repository that holds the newest draft; a workspace may hold other repositories (the download's does), so this one gets the newest
        Files.writeString(draftFile(), "Show the draft in a view\n");
        ChatStandIn chat = new ChatStandIn(null);
        IHandlerActivation activation = standInFor(chat);
        try {
            assertTrue(Await.until(() -> UiThread.call(view::askEnabled)), "the button can be pressed in a repository: " + UiThread.call(view::shown));
            UiThread.call(() -> {
                view.askTheAgent();
                return null;
            });
            assertEquals(CommitDraftView.request(repo.resolve(".git")), chat.received.get(), "the chat was sent the request that names the draft file");
            assertTrue(chat.received.get().contains(draftFile().toString()), "the request names the draft file: " + chat.received.get());
            assertTrue(chat.received.get().contains("Do not add a Co-Authored-By line"), "the request tells the agent to leave out AI attribution (the third live run: the agent added one): " + chat.received.get());
        } finally {
            UiThread.call(() -> {
                PlatformUI.getWorkbench().getService(IHandlerService.class).deactivateHandler(activation);
                return null;
            });
        }
    }

    @Test
    public void whenTheChatCannotBeAskedTheViewSaysWhy() throws Exception {
        CommitDraftView view = showTheView();
        IHandlerActivation activation = standInFor(new ChatStandIn("no agent is connected"));
        try {
            assertTrue(Await.until(() -> UiThread.call(view::askEnabled)), "the button can be pressed in a repository");
            UiThread.call(() -> {
                view.askTheAgent();
                return null;
            });
            assertTrue(UiThread.call(view::shown).contains("The chat could not be asked: no agent is connected"), "the reason is shown: " + UiThread.call(view::shown));
        } finally {
            UiThread.call(() -> {
                PlatformUI.getWorkbench().getService(IHandlerService.class).deactivateHandler(activation);
                return null;
            });
        }
    }

    @Test
    public void theHostBundleReferencesNoCommitOrPush() throws Exception {
        int scanned = 0;
        boolean ownClassSeen = false;
        List<String> offenders = new ArrayList<>();
        var entries = FrameworkUtil.getBundle(CommitDraftCheck.class).findEntries("/", "*.class", true);
        for (URL entry : Collections.list(entries)) {
            scanned++;
            ownClassSeen |= entry.getPath().endsWith("/commit/CommitDraftCheck.class");
            try (var in = entry.openStream()) {
                if (referencesCommitOrPush(in.readAllBytes())) {
                    offenders.add(entry.getPath());
                }
            }
        }
        assertTrue(scanned >= 30 && ownClassSeen, "the scan looked at the host bundle's classes, its own included (45 were measured): " + scanned);
        assertTrue(offenders.isEmpty(), "classes that reference a commit or push operation: " + offenders);
    }

    @Test
    public void theScanGoesRedOnAReferenceToACommit() {
        assertTrue(referencesCommitOrPush("....org/eclipse/jgit/api/CommitCommand....".getBytes(StandardCharsets.UTF_8)), "a class naming the commit command is found");
        assertTrue(referencesCommitOrPush("....org/eclipse/egit/core/op/PushOperation....".getBytes(StandardCharsets.UTF_8)), "a class naming the push operation is found");
        assertFalse(referencesCommitOrPush("....org/eclipse/swt/widgets/Text....".getBytes(StandardCharsets.UTF_8)), "a class naming a text area is not");
    }

    /** True when the bytes of a class file name one of the operations that commit or push (their names sit in the class file as text). */
    private static boolean referencesCommitOrPush(byte[] classFile) {
        String text = new String(classFile, StandardCharsets.ISO_8859_1);
        for (String name : new String[] { "org/eclipse/jgit/api/CommitCommand", "org/eclipse/jgit/api/PushCommand", "org/eclipse/egit/core/op/CommitOperation",
            "org/eclipse/egit/core/op/PushOperation", "org/eclipse/egit/core/op/PushOperationUI", "org/eclipse/jgit/transport/PushResult" }) {
            if (text.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
