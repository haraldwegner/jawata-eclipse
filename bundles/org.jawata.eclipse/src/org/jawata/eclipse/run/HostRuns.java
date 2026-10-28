package org.jawata.eclipse.run;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

/**
 * "Run for jawata" (plan Stage 19; contract §6.1): the person's own run configuration, started with the debug agent and the recording
 * option so jawata's debugger and profiler can attach to the running program, in Eclipse's Run mode so Eclipse's debugger never
 * connects. The agent's options ride a working copy that is never saved, so the person's configuration is not changed on disk or in
 * Eclipse. Every run is a background job the person sees in the status bar and can cancel, and the pid it answers with is all the
 * debugger needs to attach. Path A is the agent asking for it, path B the person choosing "Run for jawata" in the Run menu.
 */
public final class HostRuns {

    /** The debug agent, on loopback, on a port the JVM chooses, never holding the program at its start (contract §6.1 rule 2). */
    public static final String AGENT_OPTION = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0";

    /** The continuous, bounded recording that {@code profile jfr_dump} reads. */
    public static final String RECORDING_OPTION = "-XX:StartFlightRecording=name=jawata,settings=default,maxsize=256m,maxage=1h";

    /** The launch attribute naming the person's configuration a program was started from; it marks a program as started for jawata. */
    public static final String FOR_JAWATA = "org.jawata.eclipse.runForJawata";

    /** The launch attribute holding the program's process id. */
    public static final String PID = "org.jawata.eclipse.pid";

    /** The system property that carries a run's unique id, so the program can be found among this JVM's child processes. */
    static final String RUN_ID = "-Djawata.runId";

    /** The launch attribute saying who asked: {@link #AGENT} or {@link #PERSON}. */
    public static final String STARTED_BY = "org.jawata.eclipse.startedBy";

    /** Path A: the agent asked. */
    public static final String AGENT = "agent";

    /** Path B: the person chose "Run for jawata". */
    public static final String PERSON = "person";

    /** The family of the background jobs, so a test or a shutdown can find them. */
    public static final Object FAMILY = new Object();

    private static HostRuns shared;

    private final ILaunchManager launches;
    private final String pluginId;
    private final java.util.Set<ILaunch> live = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A program started for jawata.
     *
     * @param configuration the person's run configuration it was started from
     * @param pid           the operating-system process id the debugger attaches by
     * @param startedBy     {@link #AGENT} or {@link #PERSON}
     */
    public record JawataRun(String configuration, long pid, String startedBy) {
    }

    /**
     * @param launches the launches Eclipse's Run keeps
     * @param pluginId the plug-in a failure is reported under
     */
    public HostRuns(ILaunchManager launches, String pluginId) {
        this.launches = launches;
        this.pluginId = pluginId;
    }

    /** @return the one this plug-in uses, over Eclipse's own launches */
    public static synchronized HostRuns shared() {
        if (shared == null) {
            shared = new HostRuns(DebugPlugin.getDefault().getLaunchManager(), "org.jawata.eclipse");
        }
        return shared;
    }

    /** @return the names of the person's run configurations that can be started for jawata */
    public List<String> configurations() {
        List<String> names = new ArrayList<>();
        try {
            for (ILaunchConfiguration configuration : launches.getLaunchConfigurations()) {
                if (!configuration.isWorkingCopy() && configuration.getType().supportsMode(ILaunchManager.RUN_MODE)
                    && !configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "").isEmpty()) {
                    names.add(configuration.getName());
                }
            }
        } catch (CoreException unreadable) {
            DebugPlugin.log(unreadable);
        }
        return names;
    }

    /**
     * Starts the person's configuration for jawata as a background job; the answer completes with the program's pid once it runs, or
     * fails with why it could not start.
     *
     * @param configuration the name of the person's run configuration
     * @param startedBy     {@link #AGENT} or {@link #PERSON}
     * @return the program, once started
     */
    public CompletableFuture<JawataRun> run(String configuration, String startedBy) {
        CompletableFuture<JawataRun> started = new CompletableFuture<>();
        Job job = new Job("Running " + configuration + " for jawata") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    ILaunch launch = launch(configuration, startedBy, monitor);
                    started.complete(new JawataRun(configuration, pidOf(launch), startedBy));
                    return awaitEnd(launch, monitor);
                } catch (CoreException | RuntimeException failed) {
                    started.completeExceptionally(failed);
                    return new Status(IStatus.ERROR, pluginId, "Could not run " + configuration + " for jawata: " + failed.getMessage(), failed);
                }
            }

            @Override
            public boolean belongsTo(Object family) {
                return family == FAMILY;
            }
        };
        job.setUser(false);
        job.schedule();
        return started;
    }

    /** @return the programs started for jawata that are still running */
    public List<JawataRun> running() {
        List<JawataRun> runs = new ArrayList<>();
        live.removeIf(ILaunch::isTerminated);
        for (ILaunch launch : live) {
            String configuration = launch.getAttribute(FOR_JAWATA);
            if (configuration != null) {
                runs.add(new JawataRun(configuration, pidOf(launch), launch.getAttribute(STARTED_BY)));
            }
        }
        return runs;
    }

    /**
     * The Eclipse launch behind a program started for jawata, for a caller that needs its mode or its processes.
     *
     * @param run a program {@link #run} answered with
     * @return its launch while it is alive; empty once it has ended
     */
    public java.util.Optional<ILaunch> launchOf(JawataRun run) {
        return live.stream().filter(launch -> pidOf(launch) == run.pid()).findFirst();
    }

    private ILaunch launch(String configuration, String startedBy, IProgressMonitor monitor) throws CoreException {
        ILaunchConfiguration person = find(configuration);
        ILaunchConfigurationWorkingCopy copy = person.getWorkingCopy();
        String arguments = copy.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, "");
        String runId = java.util.UUID.randomUUID().toString();
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, withOptions(arguments, runId));
        // Not registered with Eclipse's launch manager: the debug UI reacts to every registered launch by making a console for it and
        // showing the Console view, which would take the focus from the person (spec D4). The job in the status bar is how the person sees
        // and stops the program; Eclipse still reads its output so it never blocks.
        ILaunch launch = copy.launch(ILaunchManager.RUN_MODE, monitor, false, false);
        live.add(launch);
        launch.setAttribute(PID, Long.toString(awaitPid(runId)));
        launch.setAttribute(STARTED_BY, startedBy);
        launch.setAttribute(FOR_JAWATA, configuration);
        return launch;
    }

    /**
     * The process id of the program started with this run id, found among the processes this JVM started. Eclipse keeps the system process
     * of a launch to itself, so the program is found by the run id it was given, which no other program carries.
     */
    private static long awaitPid(String runId) {
        for (int i = 0; i < 100; i++) {
            long pid = ProcessHandle.current().descendants().filter(p -> p.info().arguments().map(a -> List.of(a).contains(RUN_ID + "=" + runId))
                .orElse(false)).mapToLong(ProcessHandle::pid).findFirst().orElse(-1);
            if (pid != -1) {
                return pid;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    /** The arguments with the debug agent and the recording added, unless the person's configuration already has its own, and the run id. */
    static String withOptions(String arguments, String runId) {
        StringBuilder all = new StringBuilder(arguments);
        all.append(' ').append(RUN_ID).append('=').append(runId);
        if (!arguments.contains("-agentlib:jdwp") && !arguments.contains("-Xrunjdwp")) {
            all.append(' ').append(AGENT_OPTION);
        }
        if (!arguments.contains("StartFlightRecording")) {
            all.append(' ').append(RECORDING_OPTION);
        }
        return all.toString().trim();
    }

    private ILaunchConfiguration find(String configuration) throws CoreException {
        for (ILaunchConfiguration candidate : launches.getLaunchConfigurations()) {
            if (!candidate.isWorkingCopy() && candidate.getName().equals(configuration)) {
                return candidate;
            }
        }
        throw new CoreException(new Status(IStatus.ERROR, pluginId, "There is no run configuration named " + configuration + "."));
    }

    private static long pidOf(ILaunch launch) {
        String pid = launch.getAttribute(PID);
        return pid == null ? -1 : Long.parseLong(pid);
    }

    private IStatus awaitEnd(ILaunch launch, IProgressMonitor monitor) {
        while (!launch.isTerminated()) {
            if (monitor.isCanceled()) {
                try {
                    launch.terminate();
                } catch (CoreException unstoppable) {
                    return new Status(IStatus.ERROR, pluginId, "The program could not be stopped: " + unstoppable.getMessage(), unstoppable);
                }
                return Status.CANCEL_STATUS;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Status.CANCEL_STATUS;
            }
        }
        return Status.OK_STATUS;
    }
}
