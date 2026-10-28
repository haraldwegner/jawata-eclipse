package org.jawata.eclipse.host;

import java.util.Optional;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Starts the engine when Eclipse's workbench starts and ends it when the workbench shuts down (plan Stage 16, step 16.4): it is
 * what employs {@link EclipseEngine} in the running product, so the person never starts anything. Eclipse runs
 * {@link #earlyStartup} off the user-interface thread, so assembling the engine never holds the window. Switching workspace restarts
 * Eclipse, and the next start assembles an engine for the workspace it opens.
 */
public final class EngineStartup implements IStartup {

    private static volatile EclipseEngine running;

    @Override
    public void earlyStartup() {
        Bundle bundle = FrameworkUtil.getBundle(EngineStartup.class);
        ILog log = ILog.of(bundle);
        EclipseEngine engine = new EclipseEngine(ResourcesPlugin.getWorkspace(), log, bundle.getBundleContext());
        try {
            engine.start();
        } catch (Exception | Error failure) {
            log.log(new Status(IStatus.ERROR, EclipseEngine.PLUGIN_ID, "The jawata engine could not start: " + failure.getMessage(), failure));
            return;
        }
        running = engine;
        PlatformUI.getWorkbench().addWorkbenchListener(new IWorkbenchListener() {
            @Override
            public boolean preShutdown(IWorkbench workbench, boolean forced) {
                return true;
            }

            @Override
            public void postShutdown(IWorkbench workbench) {
                engine.close();
                running = null;
            }
        });
    }

    /** The engine this start-up began, or empty before it has started, after it has ended, and when it could not start. */
    public static Optional<EclipseEngine> running() {
        return Optional.ofNullable(running);
    }
}
