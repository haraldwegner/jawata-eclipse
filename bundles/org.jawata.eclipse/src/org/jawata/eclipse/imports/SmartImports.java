package org.jawata.eclipse.imports;

import java.io.File;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWizard;
import org.eclipse.ui.wizards.IWizardDescriptor;

/**
 * Eclipse's own Smart Import wizard, ready for one folder (plan Stage 22, spec D8). The import itself is Eclipse's: jawata creates no
 * project and writes no project file.
 *
 * <p>
 * THE PATH TAKEN, recorded as the exit criterion asks: Smart Import's classes ({@code SmartImportWizard}, {@code SmartImportJob}) are in
 * an internal package of the workbench, so jawata does not call them. It takes the signed fallback: the wizard is found by its id in the
 * public import-wizard registry, created and initialised through the public {@link IWorkbenchWizard} contract, and opened in a
 * dialog for the person to confirm. The one thing the public contract cannot do is name the folder to import; for that the wizard's own
 * {@code setInitialImportSource(File)} is called by name, and when the running Eclipse has no such method the wizard opens without a folder
 * and {@link Prepared#prefilled()} says so, so the person is asked to pick it.
 */
public final class SmartImports {

    /** The id the workbench registers the Smart Import wizard under, in File > Import (listed there as "Projects from Folder or Archive"). */
    public static final String WIZARD_ID = "org.eclipse.e4.ui.importer.wizard";

    private SmartImports() {
    }

    /**
     * A wizard and whether it was given its folder.
     *
     * @param wizard the wizard, initialised and not yet opened
     * @param prefilled false when this Eclipse cannot be told the folder, and the person picks it
     */
    public record Prepared(IWorkbenchWizard wizard, boolean prefilled) {
    }

    /**
     * Makes the wizard for one folder.
     *
     * @param workbench the workbench the wizard belongs to
     * @param folder the folder to import
     * @return the wizard
     * @throws CoreException if this Eclipse has no Smart Import wizard
     */
    public static Prepared prepare(IWorkbench workbench, File folder) throws CoreException {
        IWizardDescriptor descriptor = workbench.getImportWizardRegistry().findWizard(WIZARD_ID);
        if (descriptor == null) {
            throw new CoreException(Status.error("This Eclipse has no Smart Import wizard (" + WIZARD_ID + "), so " + folder + " cannot be imported."));
        }
        IWorkbenchWizard wizard = descriptor.createWizard();
        wizard.init(workbench, new StructuredSelection());
        try {
            wizard.getClass().getMethod("setInitialImportSource", File.class).invoke(wizard, folder);
            return new Prepared(wizard, true);
        } catch (ReflectiveOperationException unsupported) {
            return new Prepared(wizard, false);
        }
    }
}
