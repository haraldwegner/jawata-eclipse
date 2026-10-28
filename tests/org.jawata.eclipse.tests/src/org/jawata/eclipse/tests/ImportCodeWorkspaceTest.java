package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.jawata.eclipse.imports.CodeWorkspace;
import org.jawata.eclipse.imports.ImportCodeWorkspaceHandler;
import org.jawata.eclipse.imports.SmartImports;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Plan Stage 22 (W7, spec D8): File > Import a VS Code workspace reads the folders of a {@code .code-workspace} file and hands each to
 * Eclipse's Smart Import, the person confirming every one; jawata creates no project. The projects the command gives equal the projects
 * Eclipse's own import gives for the same folders. The fixture is {@code resources/vscode-workspace}: a workspace file naming two
 * folders, one relative and nested, each a plain Eclipse project.
 */
public class ImportCodeWorkspaceTest {

    private final IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
    private Path copy;

    @AfterEach
    public void leaveTheWorkspaceAsItWas() throws Exception {
        // Smart Import finishes on a job of its own after its wizard closes; a project it creates after this cleanup would meet the next test
        Await.until(() -> java.util.Arrays.stream(org.eclipse.core.runtime.jobs.Job.getJobManager().find(null))
            .noneMatch(job -> !job.isSystem() && job.getState() != org.eclipse.core.runtime.jobs.Job.SLEEPING));
        for (IProject project : root.getProjects()) {
            if (project.getName().startsWith("jawata-vsc-")) {
                project.delete(false, true, new NullProgressMonitor());
            }
        }
        if (copy != null) {
            try (Stream<Path> walk = Files.walk(copy)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The fixture copied out of the bundle, so an import never touches the shipped files. */
    private Path fixture() throws Exception {
        URL entry = FileLocator.toFileURL(FrameworkUtil.getBundle(ImportCodeWorkspaceTest.class).getEntry("resources/vscode-workspace"));
        Path source = Path.of(entry.toURI());
        copy = Files.createTempDirectory("jawata-vsc-fixture-");
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path from : (Iterable<Path>) walk::iterator) {
                Path to = copy.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(from, to);
                }
            }
        }
        return copy.resolve("one.code-workspace");
    }

    @Test
    public void relativeFoldersAreRelativeToTheFileAbsoluteOnesStandAndAnEntryWithoutAPathIsSkipped() throws Exception {
        Path dir = Files.createTempDirectory("jawata-vsc-read-");
        try {
            Path file = dir.resolve("w.code-workspace");
            Files.writeString(file, "{ \"folders\": [ { \"path\": \"a\" }, { \"path\": \"/abs/b\" }, { \"name\": \"no path\" }, { \"path\": \"c/../d\" } ] }");
            assertEquals(List.of(dir.resolve("a"), Path.of("/abs/b"), dir.resolve("d")), CodeWorkspace.folders(file));
        } finally {
            Files.deleteIfExists(dir.resolve("w.code-workspace"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void aWorkspaceFileWrittenTheWayVsCodeWritesItIsRead() throws Exception {
        Path dir = Files.createTempDirectory("jawata-vsc-jsonc-");
        try {
            Path file = dir.resolve("w.code-workspace");
            Files.writeString(file, "{\n  // the folders of this workspace\n  \"folders\": [\n    { \"path\": \"a\" }, /* second */ { \"path\": \"b\", },\n  ],\n  \"settings\": {}\n}\n");
            assertEquals(List.of(dir.resolve("a"), dir.resolve("b")), CodeWorkspace.folders(file));
        } finally {
            Files.deleteIfExists(dir.resolve("w.code-workspace"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void thePathTakenIsThePublicWizardRegistryAndTheWizardIsGivenItsFolder() throws Exception {
        Path workspace = fixture();
        File app = CodeWorkspace.folders(workspace).get(0).toFile();
        SmartImports.Prepared prepared = UiThread.call(() -> {
            try {
                return SmartImports.prepare(PlatformUI.getWorkbench(), app);
            } catch (CoreException failed) {
                throw new AssertionError(failed);
            }
        });
        assertTrue(prepared.prefilled(), "this Eclipse lets the wizard be told its folder");
        assertEquals("org.eclipse.ui.internal.wizards.datatransfer.SmartImportWizard", prepared.wizard().getClass().getName(),
            "the wizard is Eclipse's own Smart Import, reached by its id and not by a reference to its internal class");
    }

    /** The projects Eclipse's own import gives for the folders, made by hand through the public API: name to location. */
    private Map<String, URI> importedByHand(List<Path> folders) throws Exception {
        Map<String, URI> projects = new TreeMap<>();
        for (Path folder : folders) {
            IProjectDescription description = ResourcesPlugin.getWorkspace().loadProjectDescription(
                new org.eclipse.core.runtime.Path(folder.resolve(".project").toString()));
            IProject project = root.getProject(description.getName());
            description.setLocationURI(folder.toUri());
            project.create(description, new NullProgressMonitor());
            project.open(new NullProgressMonitor());
            projects.put(project.getName(), project.getLocationURI());
        }
        for (IProject project : root.getProjects()) {
            if (project.getName().startsWith("jawata-vsc-")) {
                project.delete(false, true, new NullProgressMonitor());
            }
        }
        return projects;
    }

    private Map<String, URI> theProjectsNow() {
        Map<String, URI> projects = new TreeMap<>();
        for (IProject project : root.getProjects()) {
            if (project.getName().startsWith("jawata-vsc-") && project.isOpen()) {
                projects.put(project.getName(), project.getLocationURI());
            }
        }
        return projects;
    }

    @Test
    public void theCommandOnTheFixtureGivesTheProjectsAnImportByHandGives() throws Exception {
        Path workspace = fixture();
        Map<String, URI> byHand = importedByHand(CodeWorkspace.folders(workspace));
        assertEquals(2, byHand.size(), "the fixture holds two projects: " + byHand);
        assertTrue(theProjectsNow().isEmpty(), "the by-hand projects are gone before the command runs");

        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
        Command command = commands.getCommand(ImportCodeWorkspaceHandler.COMMAND_ID);
        assertTrue(command.isDefined(), "the command is defined");
        ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(command, Map.of(ImportCodeWorkspaceHandler.FILE_PARAMETER, workspace.toString()));
        // the handler opens a modal wizard for each folder and waits for the person; the test is the person, pressing Finish in each
        Display.getDefault().asyncExec(() -> {
            try {
                handlers.executeCommand(parameterized, null);
            } catch (Exception failed) {
                throw new AssertionError("the command failed: " + failed, failed);
            }
        });
        for (int folder = 0; folder < 2; folder++) {
            WizardDialog dialog = Await.found("the Smart Import wizard for folder " + (folder + 1), () -> Optional.ofNullable(UiThread.call(() -> {
                Shell active = Display.getDefault().getActiveShell();
                return active != null && active.getData() instanceof WizardDialog opened ? opened : null;
            })));
            UiThread.call(() -> {
                assertTrue(dialog.getCurrentPage().getWizard().performFinish(), "Smart Import accepts the folder it was given");
                dialog.close();
                return null;
            });
        }
        Await.found("the projects Smart Import creates", () -> theProjectsNow().size() == 2 ? Optional.of(true) : Optional.empty());
        assertEquals(byHand, theProjectsNow(), "the same projects, at the same places, as an import by hand");
    }

    /** Presses the push button with this label (a mnemonic ignored) anywhere in the shell, as the person would. */
    private static boolean press(org.eclipse.swt.widgets.Control control, String label) {
        if (control instanceof org.eclipse.swt.widgets.Button button && button.getText().replace("&", "").equals(label)) {
            button.notifyListeners(org.eclipse.swt.SWT.Selection, new org.eclipse.swt.widgets.Event());
            return true;
        }
        if (control instanceof org.eclipse.swt.widgets.Composite composite) {
            for (org.eclipse.swt.widgets.Control child : composite.getChildren()) {
                if (press(child, label)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Starts the command on the workspace file and returns at once, as the menu does; the dialogs it opens are the test's to answer. */
    private void start(Path workspace) {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
        ParameterizedCommand parameterized = ParameterizedCommand.generateCommand(commands.getCommand(ImportCodeWorkspaceHandler.COMMAND_ID),
            Map.of(ImportCodeWorkspaceHandler.FILE_PARAMETER, workspace.toString()));
        Display.getDefault().asyncExec(() -> {
            try {
                handlers.executeCommand(parameterized, null);
            } catch (Exception failed) {
                throw new AssertionError("the command failed: " + failed, failed);
            }
        });
    }

    /** The dialog of this kind that is on top now, once there is one. */
    private static <T> T awaitDialog(String what, Class<T> kind) throws InterruptedException {
        return Await.found(what, () -> Optional.ofNullable(UiThread.call(() -> {
            Shell active = Display.getDefault().getActiveShell();
            return active != null && kind.isInstance(active.getData()) ? kind.cast(active.getData()) : null;
        })));
    }

    @Test
    public void cancellingAWizardAsksWhetherToSkipTheRestAndNoLeavesTheNextFolderToBeImported() throws Exception {
        start(fixture());
        WizardDialog first = awaitDialog("the first wizard", WizardDialog.class);
        UiThread.call(() -> press(first.getShell(), "Cancel"));
        org.eclipse.jface.dialogs.MessageDialog question = awaitDialog("the question whether to skip the rest", org.eclipse.jface.dialogs.MessageDialog.class);
        UiThread.call(() -> press(question.getShell(), "No"));
        WizardDialog second = awaitDialog("the wizard for the second folder", WizardDialog.class);
        assertTrue(second != first, "a new wizard opened for the next folder");
        UiThread.call(() -> {
            assertTrue(second.getCurrentPage().getWizard().performFinish());
            second.close();
            return null;
        });
        Await.found("the second folder's project", () -> theProjectsNow().containsKey("jawata-vsc-core") ? Optional.of(true) : Optional.empty());
        assertEquals(List.of("jawata-vsc-core"), List.copyOf(theProjectsNow().keySet()), "only the folder that was not cancelled is imported");
    }

    @Test
    public void answeringYesToTheQuestionSkipsTheFoldersThatRemain() throws Exception {
        start(fixture());
        WizardDialog first = awaitDialog("the first wizard", WizardDialog.class);
        UiThread.call(() -> press(first.getShell(), "Cancel"));
        org.eclipse.jface.dialogs.MessageDialog question = awaitDialog("the question whether to skip the rest", org.eclipse.jface.dialogs.MessageDialog.class);
        UiThread.call(() -> press(question.getShell(), "Yes"));
        Thread.sleep(2000);
        assertTrue(UiThread.call(() -> Display.getDefault().getActiveShell() == null || !(Display.getDefault().getActiveShell().getData() instanceof WizardDialog)),
            "no wizard opened for the folder that remained");
        assertTrue(theProjectsNow().isEmpty(), "and nothing was imported: " + theProjectsNow());
    }
}
