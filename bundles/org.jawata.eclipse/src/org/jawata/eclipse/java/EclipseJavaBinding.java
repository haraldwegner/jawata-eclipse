package org.jawata.eclipse.java;

import java.io.File;
import java.nio.file.Path;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.jawata.core.JavaBinding;
import org.jawata.core.JavaRuntimes;
import org.jawata.core.ModelSource;

/**
 * What Eclipse supplies to the engine's Java provider (plan Stage 16, step 16.3; contract §4.1, §5): the Java model it already
 * holds, the runtime each project is set to run on, and the JDT UI setup it has already done for itself, so the engine does none.
 */
public final class EclipseJavaBinding implements JavaBinding {

    private final ModelSource model;

    /** @param model the model the engine's tools ask; in Eclipse that is the open workspace's own */
    public EclipseJavaBinding(ModelSource model) {
        this.model = model;
    }

    @Override
    public ModelSource model() {
        return model;
    }

    @Override
    public JavaRuntimes runtimes() {
        return EclipseJavaBinding::runtimeOf;
    }

    /**
     * Whether Eclipse has done the JDT UI setup the engine's refactorings read from: in the IDE the JDT UI plug-in sets the
     * manipulation plug-in's preference node when it starts. Where it has not started (a workbench without it), nothing has, and
     * the engine must do the setup itself or its first refactoring that reads a preference is refused with an
     * {@code IllegalArgumentException} (measured: organize_imports in a bare workbench). So the answer is read from that state each
     * time it is asked, never promised.
     */
    @Override
    public boolean providesJdtUiSetup() {
        return org.eclipse.jdt.core.manipulation.JavaManipulation.getPreferenceNodeId() != null;
    }

    /** The home of the JRE the project is set to run on, else the one Eclipse itself runs on. */
    private static Path runtimeOf(IJavaProject project) {
        try {
            IVMInstall vm = JavaRuntime.getVMInstall(project);
            File home = vm == null ? null : vm.getInstallLocation();
            if (home != null) {
                return home.toPath();
            }
        } catch (CoreException e) {
            // a project whose runtime cannot be read runs on the one Eclipse runs on, as a host that knows no other does
        }
        return Path.of(System.getProperty("java.home"));
    }
}
