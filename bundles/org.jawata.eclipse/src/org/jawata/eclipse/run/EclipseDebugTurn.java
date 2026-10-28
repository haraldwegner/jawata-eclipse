package org.jawata.eclipse.run;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener;
import org.jawata.spi.host.DebugTurn;

/**
 * Whose turn it is to debug, inside Eclipse (plan Stage 19; contract §4.2, A3-2, §6.1): the person's, whenever Eclipse's own Debug
 * has a launch running, and the person wins. A program that "Run for jawata" starts is launched in Run mode, so it is never a debug
 * launch of the person's and never takes their turn. The engine is told the moment a debug launch is added, so it ends the agent's
 * sessions before the person's debugger and the agent's both hold one program.
 */
public final class EclipseDebugTurn implements DebugTurn, ILaunchesListener, AutoCloseable {

    private final ILaunchManager launches;
    private final ILog log;
    private final String pluginId;
    private final List<Runnable> told = new CopyOnWriteArrayList<>();

    /**
     * @param launches the launches Eclipse's Run and Debug keep
     * @param log      where a failure of the engine's callback is written
     * @param pluginId the plug-in that failure is reported under
     */
    public EclipseDebugTurn(ILaunchManager launches, ILog log, String pluginId) {
        this.launches = launches;
        this.log = log;
        this.pluginId = pluginId;
        launches.addLaunchListener(this);
    }

    @Override
    public boolean personIsDebugging() {
        for (ILaunch launch : launches.getLaunches()) {
            if (isDebugging(launch)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPersonStartsDebugging(Runnable endAgentSessions) {
        told.add(endAgentSessions);
    }

    @Override
    public void launchesAdded(ILaunch[] added) {
        for (ILaunch launch : added) {
            if (isDebugging(launch)) {
                tellEngine();
                return;
            }
        }
    }

    @Override
    public void launchesChanged(ILaunch[] changed) {
        // a launch changing state is not the person starting to debug; the start is the launch being added
    }

    @Override
    public void launchesRemoved(ILaunch[] removed) {
        // the turn is read from the launches that exist, so a launch going away needs no bookkeeping here
    }

    /** Stops listening to Eclipse's launches and forgets who asked to be told. */
    @Override
    public void close() {
        launches.removeLaunchListener(this);
        told.clear();
    }

    private static boolean isDebugging(ILaunch launch) {
        return ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode()) && !launch.isTerminated();
    }

    private void tellEngine() {
        for (Runnable endAgentSessions : told) {
            try {
                endAgentSessions.run();
            } catch (RuntimeException failed) {
                log.log(new Status(IStatus.ERROR, pluginId, "Ending the agent's debug sessions failed: " + failed.getMessage(), failed));
            }
        }
    }
}
