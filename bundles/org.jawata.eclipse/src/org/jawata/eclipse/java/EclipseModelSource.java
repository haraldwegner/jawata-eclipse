package org.jawata.eclipse.java;

import java.nio.file.Path;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jawata.core.IJdtService;
import org.jawata.core.ModelSource;

/**
 * Where the engine gets its Java model inside Eclipse (plan Stage 16, step 16.3; contract §4.1): the model Eclipse already holds,
 * always in place, and never opened by the engine. Opening a project is the person's act in Eclipse (File › Import, or the project
 * wizards), so {@link #open} refuses by name.
 */
public final class EclipseModelSource implements ModelSource {

    /** The reason {@link #open} refuses with, so a caller can tell this refusal from any other. */
    public static final String HOST_OWNS_WORKSPACE = "HOST_OWNS_WORKSPACE";

    private static final String TEXT = HOST_OWNS_WORKSPACE
        + ": Eclipse owns the workspace; open the project in Eclipse (File > Import) and jawata follows it.";

    private final IJdtService model;
    private final String pluginId;

    /**
     * @param model the Java model of the open workspace
     * @param pluginId the plug-in the refusal's status belongs to
     */
    public EclipseModelSource(IJdtService model, String pluginId) {
        this.model = model;
        this.pluginId = pluginId;
    }

    @Override
    public IJdtService current() {
        return model;
    }

    @Override
    public IJdtService open(Path projectRoot) throws CoreException {
        throw new CoreException(new Status(IStatus.ERROR, pluginId, TEXT));
    }
}
