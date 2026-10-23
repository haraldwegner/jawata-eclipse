package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.terminal.view.core.ITerminalService;
import org.eclipse.terminal.view.core.ITerminalsConnectorConstants;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Spec D7 (Linux): Eclipse's own Terminal, which the Java and RCP downloads ship, opens on a local shell inside Eclipse, and that shell
 * is bash: a bash process is running under this Eclipse once the terminal has opened.
 */
public class TerminalRunsBashTest {

    private static final String LOCAL_LAUNCHER = "org.eclipse.terminal.connector.local.launcher.local";

    @Test
    public void theTerminalOpensOnALocalShellAndItIsBash() throws Exception {
        List<ProcessHandle> before = bashesUnderThisEclipse();
        BundleContext context = FrameworkUtil.getBundle(TerminalRunsBashTest.class).getBundleContext();
        ServiceTracker<ITerminalService, ITerminalService> tracker = new ServiceTracker<>(context, ITerminalService.class, null);
        tracker.open();
        try {
            ITerminalService terminals = tracker.waitForService(TimeUnit.SECONDS.toMillis(30));
            assertTrue(terminals != null, "Eclipse's terminal service is there");
            Map<String, Object> properties = new HashMap<>();
            properties.put(ITerminalsConnectorConstants.PROP_DELEGATE_ID, LOCAL_LAUNCHER);
            properties.put(ITerminalsConnectorConstants.PROP_TITLE, "jawata D7");
            properties.put(ITerminalsConnectorConstants.PROP_FORCE_NEW, Boolean.TRUE);
            terminals.openConsole(properties).get(60, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            List<ProcessHandle> after = bashesUnderThisEclipse();
            while (after.size() <= before.size() && System.nanoTime() < deadline) {
                Thread.sleep(250);
                after = bashesUnderThisEclipse();
            }
            assertTrue(after.size() > before.size(), "a bash runs under Eclipse once its terminal has opened; SHELL=" + System.getenv("SHELL"));
        } finally {
            tracker.close();
        }
    }

    private static List<ProcessHandle> bashesUnderThisEclipse() {
        return ProcessHandle.current().descendants()
            .filter(p -> p.info().command().map(c -> c.endsWith("/bash")).orElse(false))
            .toList();
    }
}
