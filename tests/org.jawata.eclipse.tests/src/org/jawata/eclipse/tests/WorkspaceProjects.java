package org.jawata.eclipse.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

/**
 * The projects the host tests make in the real Eclipse workspace they run in, and the removal of them: every test makes its own,
 * uniquely named, and removes it, so no test leaves anything for the next.
 */
final class WorkspaceProjects {

    /** The class {@link #java} puts in every Java project it makes, as {@code p.Greeter}. */
    static final String GREETER = "package p;\n\npublic class Greeter {\n    public String hello() {\n        return \"hi\";\n    }\n}\n";

    private WorkspaceProjects() {
    }

    /** A fresh, uniquely named, open project with no nature. */
    static IProject open(String prefix) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(prefix + UUID.randomUUID());
        project.create(null);
        project.open(null);
        return project;
    }

    /** A fresh open Java project: source folder {@code src} holding {@link #GREETER}, output {@code bin}, Eclipse's default runtime. */
    static IProject java(String prefix) throws CoreException {
        IProject project = open(prefix);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(description, null);
        IFolder src = project.getFolder("src");
        src.create(true, true, null);
        project.getFolder("bin").create(true, true, null);
        IJavaProject java = JavaCore.create(project);
        java.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(src.getFullPath()),
            JavaCore.newContainerEntry(Path.fromPortableString("org.eclipse.jdt.launching.JRE_CONTAINER")) },
            project.getFolder("bin").getFullPath(), null);
        IFolder pkg = src.getFolder("p");
        pkg.create(true, true, null);
        pkg.getFile("Greeter.java").create(new ByteArrayInputStream(GREETER.getBytes(StandardCharsets.UTF_8)), true, null);
        return project;
    }

    /** Removes the projects from the workspace and from disk; one that cannot be removed is left to the workspace's own cleanup. */
    static void deleteQuietly(IProject... projects) {
        for (IProject project : projects) {
            try {
                project.delete(true, true, null);
            } catch (CoreException e) {
                // a project that cannot be removed is left to the workspace's own cleanup
            }
        }
    }
}
