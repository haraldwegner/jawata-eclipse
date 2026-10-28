package org.jawata.eclipse.host;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jawata.spi.host.EngineLifecycle;
import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;

/**
 * What happens when the engine's listener fails inside Eclipse (plan Stage 16, contract §4.2): the person is told and the failure goes
 * to Eclipse's log. The headless host ends its process with an exit code its supervisor reads; Eclipse is the person's tool and must
 * stay open, so nothing here stops it.
 */
public final class EclipseEngineLifecycle implements EngineLifecycle {

    private final ILog log;
    private final PersonChannel person;
    private final String pluginId;

    /**
     * @param log      where failures are recorded (Eclipse's log)
     * @param person   how the person is told
     * @param pluginId the plug-in the log entries belong to
     */
    public EclipseEngineLifecycle(ILog log, PersonChannel person, String pluginId) {
        this.log = log;
        this.person = person;
        this.pluginId = pluginId;
    }

    @Override
    public void listenerDeaf() {
        String text = "jawata's listener stopped answering; restart Eclipse to bring the jawata engine back.";
        log.log(new Status(IStatus.ERROR, pluginId, text));
        person.tell(Notice.error(text));
    }

    @Override
    public void cannotListen(int port, Exception cause) {
        String text = "jawata could not open its listener" + (port == 0 ? "" : " on port " + port) + "; its tools are not reachable from other programs.";
        log.log(new Status(IStatus.ERROR, pluginId, text, cause));
        person.tell(Notice.error(text));
    }
}
