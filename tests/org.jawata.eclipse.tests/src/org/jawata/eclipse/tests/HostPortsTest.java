package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jawata.eclipse.host.EclipseDirectories;
import org.jawata.eclipse.host.EclipseEndpointPolicy;
import org.jawata.eclipse.host.EclipseEngineLifecycle;
import org.jawata.eclipse.host.EclipsePersonChannel;
import org.jawata.eclipse.host.EclipseSettings;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.spi.host.McpEndpoint;
import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/**
 * Plan Stage 16, step 16.1: the host ports that need no Java model, run inside a real Eclipse. Each test names what the port promises the
 * engine, in the Eclipse host's terms (contract §4.2, §6): loopback with no key, a failure that never ends Eclipse, settings from the
 * preference store, folders under the workspace the person has open, and work shown as background jobs.
 */
public class HostPortsTest {

    private static final String PLUGIN = "org.jawata.eclipse";

    /** What a test records of Eclipse's log while it runs. */
    private static final class Recorded implements ILogListener, AutoCloseable {
        final List<IStatus> entries = new CopyOnWriteArrayList<>();
        final ILog log = ILog.of(FrameworkUtil.getBundle(HostPortsTest.class));

        Recorded() {
            log.addLogListener(this);
        }

        @Override
        public void logging(IStatus status, String plugin) {
            entries.add(status);
        }

        @Override
        public void close() {
            log.removeLogListener(this);
        }
    }

    @Test
    public void theEndpointIsLoopbackOnAnyPortWithNoKey() {
        EclipseEndpointPolicy policy = new EclipseEndpointPolicy();

        assertEquals("127.0.0.1", policy.bindAddress());
        assertEquals(0, policy.port(), "0 asks the operating system for a free port");
        assertEquals(Optional.empty(), policy.key(), "no key inside Eclipse (his ruling A3-1)");
    }

    @Test
    public void whoAnnouncesTheEngineIsToldWhenTheListenerComesUpAndEvenIfItAlreadyHas() {
        EclipseEndpointPolicy policy = new EclipseEndpointPolicy();
        McpEndpoint at = new McpEndpoint(URI.create("http://127.0.0.1:41234/mcp"), Optional.empty());
        List<McpEndpoint> told = new ArrayList<>();

        policy.onListening(told::add);
        assertTrue(told.isEmpty(), "nothing to announce before the listener is up");
        assertEquals(Optional.empty(), policy.reached());

        policy.listening(at);
        assertEquals(List.of(at), told);
        assertEquals(Optional.of(at), policy.reached());

        List<McpEndpoint> late = new ArrayList<>();
        policy.onListening(late::add);
        assertEquals(List.of(at), late, "one who asks after the listener is up is told at once");
    }

    @Test
    public void aListenerFailureIsLoggedAndToldAndDoesNotEndEclipse() {
        List<Notice> told = new ArrayList<>();
        PersonChannel person = new PersonChannel() {
            @Override
            public void tell(Notice notice) {
                told.add(notice);
            }

            @Override
            public Activity begin(String label) {
                return PersonChannel.SILENT.begin(label);
            }
        };
        try (Recorded recorded = new Recorded()) {
            EclipseEngineLifecycle lifecycle = new EclipseEngineLifecycle(recorded.log, person, PLUGIN);

            lifecycle.cannotListen(41234, new java.io.IOException("address in use"));
            lifecycle.listenerDeaf();

            assertEquals(2, told.size(), "both failures reach the person");
            assertTrue(told.stream().allMatch(n -> n.level() == Notice.Level.ERROR));
            assertTrue(told.get(0).text().contains("41234"), "the port the listener could not open is named: " + told.get(0).text());
            assertEquals(2, recorded.entries.stream().filter(s -> s.getSeverity() == IStatus.ERROR).count(), "both failures reach Eclipse's log");
        }
        // reaching this line is the proof that nothing ended the process
    }

    @Test
    public void aSettingIsThePersonsPreferenceElseTheHostDefaultElseTheFrameworkProperty() throws Exception {
        String node = "org.jawata.eclipse.tests.settings." + UUID.randomUUID();
        IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(node);
        try {
            EclipseSettings settings = new EclipseSettings(preferences,
                Map.of("jawata.runtime.dir", "/state/runtime", "jawata.launched.too", "/default"),
                key -> "osgi.instance.area".equals(key) ? "file:/workspace/" : "jawata.launched.too".equals(key) ? "/launched" : null);

            assertEquals(Optional.of("/launched"), settings.get("jawata.launched.too"), "a property given at launch beats the host default");

            assertEquals(Optional.of("/state/runtime"), settings.get("jawata.runtime.dir"), "the host default when nothing is set");
            preferences.put("jawata.runtime.dir", "/chosen");
            assertEquals(Optional.of("/chosen"), settings.get("jawata.runtime.dir"), "the person's preference wins over the default");
            assertEquals(Optional.of("file:/workspace/"), settings.get("osgi.instance.area"), "a framework property answers when nothing else does");
            assertEquals(Optional.empty(), settings.get("jawata.unknown"), "an unknown key is absent, not blank");
        } finally {
            preferences.removeNode();
        }
    }

    @Test
    public void theFoldersComeFromTheSettingsAndTheMemoryIsLookedForBesideTheOpenProjects() throws Exception {
        Path project = Files.createTempDirectory("jawata-eclipse-project");
        try {
            Files.writeString(project.resolve("CLAUDE.md"), "# memory the person keeps\n");
            IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode("org.jawata.eclipse.tests.dirs." + UUID.randomUUID());
            EclipseSettings settings = new EclipseSettings(preferences, Map.of(
                SettingKeys.RUNTIME_DIR.name(), "/state/runtime",
                SettingKeys.COVERAGE_DIR.name(), "/state/coverage",
                SettingKeys.TOOLS_DIR.name(), "/state/tools"), key -> null);

            EclipseDirectories directories = new EclipseDirectories(settings, () -> List.of(project));

            assertEquals(Path.of("/state/runtime"), directories.runtimeDir());
            assertEquals(Path.of("/state/coverage"), directories.coverageDir());
            assertEquals(Path.of("/state/tools"), directories.toolsDir());
            assertTrue(directories.memoryRoots().contains(project.resolve("CLAUDE.md")),
                "the memory file beside an open project is found: " + directories.memoryRoots());
        } finally {
            Files.deleteIfExists(project.resolve("CLAUDE.md"));
            Files.deleteIfExists(project);
        }
    }

    @Test
    public void aNoticeIsLoggedAtItsLevel() {
        try (Recorded recorded = new Recorded()) {
            EclipsePersonChannel channel = new EclipsePersonChannel(recorded.log, PLUGIN);

            channel.tell(Notice.warning("jawata is short of memory"));
            channel.tell(Notice.error("jawata could not do it"));

            List<IStatus> mine = recorded.entries.stream().filter(s -> PLUGIN.equals(s.getPlugin())).toList();
            assertEquals(List.of(IStatus.WARNING, IStatus.ERROR), mine.stream().map(IStatus::getSeverity).toList());
            assertEquals("jawata is short of memory", mine.get(0).getMessage());
        }
    }

    @Test
    public void longWorkIsABackgroundJobUntilItIsClosed() throws Exception {
        try (Recorded recorded = new Recorded()) {
            EclipsePersonChannel channel = new EclipsePersonChannel(recorded.log, PLUGIN);
            String label = "jawata test work " + UUID.randomUUID();

            PersonChannel.Activity work = channel.begin(label);
            Job running = null;
            for (int i = 0; i < 50 && running == null; i++) {
                for (Job job : Job.getJobManager().find(null)) {
                    if (label.equals(job.getName())) {
                        running = job;
                    }
                }
                if (running == null) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(running, "the work shows as a job in Eclipse's progress");
            assertFalse(running.isUser(), "a background job, not one the person must watch");
            work.step("halfway");

            work.close();
            running.join();
            assertEquals(Job.NONE, running.getState(), "closing the work ends the job");
        }
    }
}
