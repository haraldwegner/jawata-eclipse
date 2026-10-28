package org.jawata.eclipse.host;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.jawata.spi.host.WorkspaceWrites;

/**
 * How the engine's changes reach the person's files inside Eclipse (plan Stage 18, contract §6, W3). A change is performed through
 * LTK, which writes through the file buffer an open editor holds, never around it; a change the person keeps lands on Edit › Undo as
 * one step, and a trial, which is applied only to be judged, never does. A change over a file with unsaved editor text, or a file
 * that is not UTF-8, is refused by name before anything is touched, so the person's text is never overwritten.
 */
public final class EclipseWrites implements WorkspaceWrites {

    /** The refusal for a file whose editor holds text that is not saved yet. */
    public static final String UNSAVED_IN_EDITOR = "UNSAVED_IN_EDITOR";

    /** The refusal for a file the engine cannot rewrite without changing its encoding. */
    public static final String CHARSET_NOT_UTF8 = "CHARSET_NOT_UTF8";

    private final String pluginId;

    /** @param pluginId the plug-in a refusal's status belongs to */
    public EclipseWrites(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public Applied perform(Change change, IProgressMonitor monitor) throws CoreException {
        return perform(change, WriteMode.ACCEPTED, monitor);
    }

    @Override
    public Applied perform(Change change, WriteMode mode, IProgressMonitor monitor) throws CoreException {
        Optional<String> refused = refusal(targetsOf(change));
        if (refused.isPresent()) {
            throw new CoreException(new Status(IStatus.ERROR, pluginId, refused.get()));
        }
        PerformChangeOperation operation = new PerformChangeOperation(change);
        if (mode == WriteMode.ACCEPTED) {
            operation.setUndoManager(RefactoringCore.getUndoManager(), change.getName());
        }
        operation.run(monitor);
        return new Applied(operation.getUndoChange(), operation.getValidationStatus());
    }

    @Override
    public Optional<String> refusal(Collection<IFile> targets) {
        for (IFile file : targets) {
            ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            if (buffer != null && buffer.isDirty()) {
                return Optional.of(UNSAVED_IN_EDITOR + ": " + file.getFullPath() + " has text in an editor that is not saved; save or close it "
                    + "and ask again. Nothing was changed.");
            }
            try {
                String charset = file.exists() ? file.getCharset() : StandardCharsets.UTF_8.name();
                // A file in another encoding is only at risk when it holds bytes outside ASCII: a pure ASCII file reads the same in every
                // encoding, so a workspace whose default is not UTF-8 (the Windows default) does not refuse every file.
                if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(charset) && hasNonAscii(file)) {
                    return Optional.of(CHARSET_NOT_UTF8 + ": " + file.getFullPath() + " is " + charset + ", and jawata rewrites only UTF-8 files. "
                        + "Nothing was changed.");
                }
            } catch (CoreException unreadable) {
                return Optional.of(CHARSET_NOT_UTF8 + ": the encoding of " + file.getFullPath() + " cannot be read (" + unreadable.getMessage()
                    + "). Nothing was changed.");
            }
        }
        return Optional.empty();
    }

    /** Whether the file holds any byte outside ASCII; a file that cannot be read counts as holding one, so it is refused and not guessed at. */
    private static boolean hasNonAscii(IFile file) {
        try (java.io.InputStream in = file.getContents()) {
            for (int b = in.read(); b != -1; b = in.read()) {
                if (b >= 0x80) {
                    return true;
                }
            }
            return false;
        } catch (CoreException | java.io.IOException unreadable) {
            return true;
        }
    }

    /** The files a change acts on, read from the change itself, so the check is made before anything is written. */
    private static Set<IFile> targetsOf(Change change) {
        Set<IFile> files = new LinkedHashSet<>();
        collect(change, files);
        return files;
    }

    private static void collect(Change change, Set<IFile> into) {
        if (change == null) {
            return;
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collect(child, into);
            }
            return;
        }
        if (change instanceof TextFileChange textFile) {
            into.add(textFile.getFile());
            return;
        }
        Object modified = change.getModifiedElement();
        IResource resource = modified instanceof IJavaElement element ? element.getResource() : modified instanceof IResource r ? r : null;
        if (resource instanceof IFile file) {
            into.add(file);
        }
    }
}
