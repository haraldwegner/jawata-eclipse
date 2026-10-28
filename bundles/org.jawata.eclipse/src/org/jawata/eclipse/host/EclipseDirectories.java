package org.jawata.eclipse.host;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.jawata.mcp.engine.DefaultEngineDirectories;
import org.jawata.spi.host.EngineSettings;

/**
 * The engine's folders inside Eclipse (plan Stage 16, contract §4.2). The folder rules are the engine's own
 * ({@link DefaultEngineDirectories}: a named folder first, else under the workspace, else the temporary folder); this class adds only
 * what is a fact of Eclipse: the folders of the projects the open workspace holds, which the memory the person keeps is looked for
 * beside. Where each folder is comes from the settings the host answers ({@link EclipseSettings}), whose defaults put the engine's
 * working folders under the plug-in's state area.
 */
public final class EclipseDirectories extends DefaultEngineDirectories {

    private final Supplier<List<Path>> openProjects;

    /**
     * @param settings     where the named folders and the workspace root are read from
     * @param openProjects the folders of the projects open in the workspace now
     */
    public EclipseDirectories(EngineSettings settings, Supplier<List<Path>> openProjects) {
        super(settings);
        this.openProjects = openProjects;
    }

    @Override
    protected List<Path> projectDirs() {
        return openProjects.get();
    }
}
