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

    /** The answers of the trials performed here that nobody has kept yet; weak, so a trial the gate took back is not held for ever. */
    private final java.util.Map<Applied, Change> trials = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

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
        Applied applied = new Applied(operation.getUndoChange(), operation.getValidationStatus());
        if (mode == WriteMode.TRIAL) {
            // remembered only until it is kept: the engine hands this very answer back if its gate accepts the change (plan 25a.6, ledger A18-1)
            trials.put(applied, change);
        } else if (mode == WriteMode.UNDO && operation.changeExecuted()) {
            // the engine took back a change it kept: the step that change left on Edit > Undo is undone now, so it goes (ledger A18-2)
            settleKeptStep(change);
        }
        return applied;
    }

    /**
     * The engine has just performed {@code undoChange}, the undo of a change this host kept, so the step that kept change left on the
     * person's Edit &gt; Undo is stale: pressing Undo on it would undo what is already undone. The step is the one whose undo change is this
     * very instance (LTK's operation holds it as its active change once the step is on the history), and only that step is dropped; the
     * person's other steps stay, which {@code IUndoManager.flush()} would not allow. A change that was never kept is on no step, and
     * nothing is touched. The take-back itself is not listed: it was performed without an undo manager.
     */
    private static void settleKeptStep(Change undoChange) {
        org.eclipse.core.commands.operations.IOperationHistory history = org.eclipse.core.commands.operations.OperationHistoryFactory.getOperationHistory();
        for (org.eclipse.core.commands.operations.IUndoableOperation step : history.getUndoHistory(org.eclipse.ui.ide.undo.WorkspaceUndoUtil.getWorkspaceUndoContext())) {
            if (changeOf(step) == undoChange) {
                history.replaceOperation(step, new org.eclipse.core.commands.operations.IUndoableOperation[0]);
                return;
            }
        }
    }

    /**
     * The change LTK's operation of a step currently holds (for a step on the history: its undo change), or null when the step is not a
     * change of LTK's. The operation class is LTK-internal and not exported, so it is asked by name; a step it cannot ask is left alone.
     */
    private static Change changeOf(org.eclipse.core.commands.operations.IUndoableOperation step) {
        org.eclipse.core.commands.operations.IUndoableOperation inner = step instanceof org.eclipse.core.commands.operations.TriggeredOperations triggered
            ? triggered.getTriggeringOperation() : step;
        try {
            return inner.getClass().getMethod("getChange").invoke(inner) instanceof Change change ? change : null;
        } catch (ReflectiveOperationException | RuntimeException notLtksOperation) {
            return null;
        }
    }

    /**
     * The gate accepted a change this host performed as a trial: it becomes one step on Edit &gt; Undo now, under {@code name}, which is
     * what an accepted change is. It is added once however often it is asked (the first call takes it out of the trials), and only if it
     * was performed here as a trial: an accepted change is on the history already, and a trial the gate took back is never asked about.
     */
    @Override
    public boolean keep(Applied applied, String name) {
        if (applied == null || applied.undo() == null) {
            return false;
        }
        Change performed = trials.remove(applied);
        if (performed == null) {
            return true;
        }
        // The same four steps PerformChangeOperation takes for a change it performs with an undo manager: the manager opens an operation for
        // the performed change, closes it, and only then takes the undo change into that operation (addUndo does nothing with none open).
        org.eclipse.ltk.core.refactoring.IUndoManager manager = RefactoringCore.getUndoManager();
        try {
            manager.aboutToPerformChange(performed);
            manager.changePerformed(performed, true);
            applied.undo().initializeValidationData(new org.eclipse.core.runtime.NullProgressMonitor());
            manager.addUndo(name, applied.undo());
            return true;
        } catch (RuntimeException unkeepable) {
            trials.put(applied, performed);
            return false;
        }
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
