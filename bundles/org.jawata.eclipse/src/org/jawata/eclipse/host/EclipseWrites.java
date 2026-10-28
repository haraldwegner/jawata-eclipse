package org.jawata.eclipse.host;

import java.util.Collection;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.spi.host.WorkspaceWrites;

/**
 * How the engine's changes reach the person's files inside Eclipse. Until Stage 18 (contract §6, W3) it reaches none: every write is
 * refused by a named reason, so a tool that would change a file says why it did not, and nothing is written around the editors the
 * person has open. Stage 18 replaces the refusal with writes that land on Edit › Undo and never overwrite unsaved text.
 */
public final class EclipseWrites implements WorkspaceWrites {

    /** The reason every write is refused with, by name, so a caller can tell this refusal from any other. */
    public static final String WRITES_NOT_AVAILABLE = "WRITES_NOT_AVAILABLE";

    private static final String TEXT = WRITES_NOT_AVAILABLE
        + ": jawata in Eclipse reads the workspace and does not write to it yet; the change was not made.";

    private final String pluginId;

    /** @param pluginId the plug-in the refusal's status belongs to */
    public EclipseWrites(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public Applied perform(Change change, IProgressMonitor monitor) throws CoreException {
        throw new CoreException(new Status(IStatus.ERROR, pluginId, TEXT));
    }

    @Override
    public Optional<String> refusal(Collection<IFile> targets) {
        return Optional.of(TEXT);
    }
}
