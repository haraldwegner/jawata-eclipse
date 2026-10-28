package org.jawata.eclipse.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IPath;
import org.jawata.spi.host.HostWorkspace;

/**
 * Which projects the person's open Eclipse workspace holds (plan Stage 16, contract §4.2, §6.2). Eclipse owns the workspace: the engine
 * follows it and never loads, adds or removes a project, so {@link #ownsWorkspace} is false and the workspace verbs answer
 * {@code HOST_OWNS_WORKSPACE}. A project with no folder on a local disk is left out, since nothing can be analysed there.
 */
public final class EclipseWorkspace implements HostWorkspace {

    private final IWorkspaceRoot root;

    /** @param root the root of the workspace Eclipse has open */
    public EclipseWorkspace(IWorkspaceRoot root) {
        this.root = root;
    }

    @Override
    public List<ProjectFolder> folders() {
        List<ProjectFolder> folders = new ArrayList<>();
        for (IProject project : root.getProjects()) {
            IPath location = project.getLocation();
            if (location != null) {
                folders.add(new ProjectFolder(project.getName(), Path.of(location.toOSString()), project.isOpen()));
            }
        }
        return folders;
    }

    @Override
    public boolean ownsWorkspace() {
        return false;
    }
}
