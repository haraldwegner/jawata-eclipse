package org.jawata.eclipse.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.SyncReport;

/**
 * What the engine asks of Eclipse about files changed on disk (plan Stage 16, contract §4.2): Eclipse keeps its own model of the
 * workspace, so before a tool answers the host refreshes the workspace from disk, waits for Eclipse's own build to settle, and reports
 * which files changed since the last call, as Eclipse's change events saw them. Build products Eclipse marks derived are not counted:
 * the engine reasons about source, not about what the compiler wrote. Close it when the engine stops, so Eclipse stops telling it.
 */
public final class EclipseReconciler implements Reconciler, IResourceChangeListener, AutoCloseable {

    /** The most changed paths one report names, so a large checkout cannot make a report that large. */
    static final int MOST_PATHS = 200;

    private final IWorkspace workspace;
    private final Set<String> changed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger added = new AtomicInteger();

    /** @param workspace the workspace Eclipse has open; this starts listening to its changes */
    public EclipseReconciler(IWorkspace workspace) {
        this.workspace = workspace;
        workspace.addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null) {
            return;
        }
        try {
            delta.accept(each -> {
                IResource resource = each.getResource();
                if (resource.getType() == IResource.PROJECT && each.getKind() == IResourceDelta.ADDED) {
                    added.incrementAndGet();
                }
                boolean contentChanged = each.getKind() != IResourceDelta.CHANGED
                    || (each.getFlags() & (IResourceDelta.CONTENT | IResourceDelta.REPLACED)) != 0;
                if (resource.getType() == IResource.FILE && contentChanged && !resource.isDerived() && resource.getLocation() != null) {
                    changed.add(resource.getLocation().toOSString());
                }
                return true;
            });
        } catch (CoreException e) {
            // a delta that cannot be read is simply not counted; the next call's refresh produces the change again
        }
    }

    @Override
    public SyncReport syncBeforeCall() throws CoreException {
        long start = System.nanoTime();
        workspace.getRoot().refreshLocal(IResource.DEPTH_INFINITE, null);
        long scanned = System.nanoTime();
        try {
            Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (OperationCanceledException e) {
            // the wait was cancelled; what has changed is still reported
        }
        List<String> paths = new ArrayList<>(changed);
        changed.removeAll(paths);
        Collections.sort(paths);
        int newProjects = added.getAndSet(0);
        long end = System.nanoTime();
        List<String> named = paths.size() > MOST_PATHS ? paths.subList(0, MOST_PATHS) : paths;
        return new SyncReport(false, newProjects, paths.size(), 0, scanned - start, end - start, false, List.copyOf(named));
    }

    @Override
    public Set<String> rebuild(BuildScope scope, BuildKind kind, IProgressMonitor monitor) throws CoreException {
        Set<String> built = new LinkedHashSet<>();
        for (String name : scope.projects()) {
            IProject project = workspace.getRoot().getProject(name);
            if (!project.exists() || !project.isOpen()) {
                continue;
            }
            try {
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                if (kind == BuildKind.CLEAN_FULL) {
                    project.build(IncrementalProjectBuilder.CLEAN_BUILD, monitor);
                }
                project.build(kind == BuildKind.INCREMENTAL ? IncrementalProjectBuilder.INCREMENTAL_BUILD : IncrementalProjectBuilder.FULL_BUILD,
                    monitor);
                built.add(name);
            } catch (CoreException e) {
                // a project whose build failed is left out, so a caller never reads "built" where it was not
            }
        }
        return built;
    }

    @Override
    public void close() {
        workspace.removeResourceChangeListener(this);
    }
}
