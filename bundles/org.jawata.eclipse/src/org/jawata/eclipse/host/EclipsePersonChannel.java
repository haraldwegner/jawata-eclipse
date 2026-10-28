package org.jawata.eclipse.host;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;

/**
 * How the engine reaches the person inside Eclipse (plan Stage 16, contract §4.2): never a window. A notice goes to Eclipse's log and
 * shows for a moment as a background job in the status bar; a piece of long work is a background job that stays there, with its
 * current step as the job's sub-task, until the work is closed. Nothing takes the person's focus or switches their perspective (spec D4).
 */
public final class EclipsePersonChannel implements PersonChannel {

    private final ILog log;
    private final String pluginId;

    /**
     * @param log      where notices are recorded (Eclipse's log)
     * @param pluginId the plug-in the log entries belong to
     */
    public EclipsePersonChannel(ILog log, String pluginId) {
        this.log = log;
        this.pluginId = pluginId;
    }

    @Override
    public void tell(Notice notice) {
        int severity = switch (notice.level()) {
            case INFO -> IStatus.INFO;
            case WARNING -> IStatus.WARNING;
            case ERROR -> IStatus.ERROR;
        };
        log.log(new Status(severity, pluginId, notice.text()));
        Job shown = new Job(notice.text()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                return Status.OK_STATUS;
            }
        };
        shown.setUser(false);
        shown.schedule();
    }

    @Override
    public Activity begin(String label) {
        RunningWork work = new RunningWork(label);
        work.setUser(false);
        work.schedule();
        return work;
    }

    /** One piece of long work: a job that lasts until it is closed or the person cancels it in the progress view. */
    private static final class RunningWork extends Job implements Activity {

        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile IProgressMonitor monitor;

        RunningWork(String label) {
            super(label);
        }

        @Override
        protected IStatus run(IProgressMonitor running) {
            monitor = running;
            running.beginTask(getName(), IProgressMonitor.UNKNOWN);
            try {
                while (!closed.await(200, TimeUnit.MILLISECONDS)) {
                    if (running.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Status.CANCEL_STATUS;
            } finally {
                running.done();
            }
            return Status.OK_STATUS;
        }

        @Override
        public void step(String what) {
            IProgressMonitor now = monitor;
            if (now != null) {
                now.subTask(what);
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
