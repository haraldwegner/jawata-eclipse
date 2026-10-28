package org.jawata.eclipse.imports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * File > Import a VS Code workspace (plan Stage 22, spec D8): reads the folders of a {@code .code-workspace} file and opens Eclipse's
 * Smart Import wizard for each, one after the other, so the person confirms every import. jawata creates no project and writes no project
 * file; see {@link SmartImports} for the path taken. A folder that does not exist is left out and named at the end; cancelling one
 * wizard skips that folder only.
 */
public final class ImportCodeWorkspaceHandler extends AbstractHandler {

    /** The id plugin.xml gives the command. */
    public static final String COMMAND_ID = "org.jawata.eclipse.importCodeWorkspace";

    /** The optional parameter that names the workspace file, so a script or a test need not use the file dialog. */
    public static final String FILE_PARAMETER = COMMAND_ID + ".file";

    private static final String TITLE = "Import a VS Code workspace";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        Shell shell = window.getShell();
        String named = event.getParameter(FILE_PARAMETER);
        if (named == null) {
            FileDialog chooser = new FileDialog(shell, SWT.OPEN);
            chooser.setText(TITLE);
            chooser.setFilterNames(new String[] {"VS Code workspace (*.code-workspace)", "All files"});
            chooser.setFilterExtensions(new String[] {"*.code-workspace", "*"});
            named = chooser.open();
            if (named == null) {
                return null;
            }
        }
        List<Path> folders;
        try {
            folders = CodeWorkspace.folders(Path.of(named));
        } catch (IOException unreadable) {
            MessageDialog.openError(shell, TITLE, "The workspace file " + named + " cannot be read: " + unreadable.getMessage());
            return null;
        }
        List<Path> missing = new ArrayList<>();
        for (int index = 0; index < folders.size(); index++) {
            Path folder = folders.get(index);
            if (!folder.toFile().isDirectory()) {
                missing.add(folder);
                continue;
            }
            try {
                SmartImports.Prepared prepared = SmartImports.prepare(window.getWorkbench(), folder.toFile());
                if (!prepared.prefilled()) {
                    MessageDialog.openInformation(shell, TITLE, "This Eclipse cannot choose the folder for you. In the wizard that opens, choose:\n" + folder);
                }
                int answer = new WizardDialog(shell, prepared.wizard()).open();
                int left = folders.size() - index - 1;
                if (answer == Window.CANCEL && left > 0 && MessageDialog.openQuestion(shell, TITLE,
                    "You cancelled the import of " + folder + ".\nSkip the " + left + " remaining folder" + (left == 1 ? "" : "s") + " too?")) {
                    break;
                }
            } catch (CoreException failed) {
                throw new ExecutionException(failed.getMessage(), failed);
            }
        }
        if (!missing.isEmpty()) {
            MessageDialog.openInformation(shell, TITLE, "These folders of the workspace do not exist and were not imported:\n" + missing);
        }
        return null;
    }
}
