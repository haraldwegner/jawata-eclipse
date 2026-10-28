package org.jawata.eclipse.host;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jawata.eclipse.java.EclipseJavaBinding;
import org.jawata.eclipse.java.EclipseJdtService;
import org.jawata.eclipse.java.EclipseModelSource;
import org.jawata.mcp.engine.EngineState;
import org.jawata.mcp.engine.JawataEngine;
import org.jawata.mcp.engine.WhiteboardTracker;
import org.jawata.mcp.transport.HttpTransport;
import org.jawata.mcp.transport.Transport;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.HostWorkspace.ProjectFolder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * One engine running inside Eclipse for the workspace Eclipse has open (plan Stage 16, step 16.4; contract §4.3): it builds the host
 * from the ports of this plug-in, assembles the engine over it, registers the host as the service the Java provider binds to, and
 * serves on a thread of its own, so Eclipse's user interface never waits on it. {@link #close} ends all of it, in the opposite
 * order. A second workspace means a second Eclipse, so this is never restarted in place.
 */
public final class EclipseEngine implements AutoCloseable {

    /** The plug-in everything this engine reports is reported under. */
    public static final String PLUGIN_ID = "org.jawata.eclipse";

    /** How long one engine operation may run before it is abandoned, until the person's settings say otherwise (Stage 17). */
    private static final int OPERATION_TIMEOUT_SECONDS = 30;

    /** How full the heap is after a collection before the person is told to raise Eclipse's memory. */
    private static final double MEMORY_NOTICE_FRACTION = 0.9;

    private final IWorkspace workspace;
    private final ILog log;
    private final BundleContext framework;
    private final EclipseEndpointPolicy endpoint = new EclipseEndpointPolicy();

    private JawataEngine engine;
    private EclipseJdtService model;
    private EclipseReconciler reconciler;
    private ServiceRegistration<EngineHost> hostRegistration;
    private WhiteboardTracker whiteboardTracker;
    private Thread serving;
    private EngineNoteWriter note;
    private MemoryNotice memory;

    /**
     * @param workspace the workspace Eclipse has open
     * @param log       where the engine's problems are written, under {@link #PLUGIN_ID}
     * @param framework the OSGi framework the host is registered in
     */
    public EclipseEngine(IWorkspace workspace, ILog log, BundleContext framework) {
        this.workspace = workspace;
        this.log = log;
        this.framework = framework;
    }

    /** Where the engine listens; it tells who asks once the listener is up. */
    public EclipseEndpointPolicy endpoint() {
        return endpoint;
    }

    /**
     * Assembles the engine, lets the Java provider bind, starts it and begins serving. Returns when the engine is running; the
     * listener comes up on its own thread a moment later, and {@link #endpoint} says when.
     *
     * @throws Exception if the engine cannot be assembled or started; nothing is left running
     */
    public synchronized void start() throws Exception {
        if (engine != null) {
            return;
        }
        EclipsePersonChannel person = new EclipsePersonChannel(log, PLUGIN_ID);
        EclipseWorkspace folders = new EclipseWorkspace(workspace.getRoot());
        EclipseSettings settings = new EclipseSettings(InstanceScope.INSTANCE.getNode(PLUGIN_ID), Map.of(), System::getProperty);
        EclipseDirectories directories = new EclipseDirectories(settings,
            () -> folders.folders().stream().filter(ProjectFolder::open).map(ProjectFolder::path).toList());
        model = new EclipseJdtService(workspace.getRoot(), OPERATION_TIMEOUT_SECONDS);
        reconciler = new EclipseReconciler(workspace);
        EclipseHost host = new EclipseHost(folders, new EclipseEngineLifecycle(log, person, PLUGIN_ID), endpoint, settings, directories,
            new EclipseJavaBinding(new EclipseModelSource(model, PLUGIN_ID)), reconciler, new EclipseWrites(PLUGIN_ID), person);
        try {
            // The note for studio and sibling engines is written the moment the listener is up (contract §14.1).
            note = new EngineNoteWriter(workspace, folders,
                settings.get(org.jawata.mcp.engine.SettingKeys.ENGINE_NOTES_DIR.name()).filter(dir -> !dir.isBlank()).map(java.nio.file.Path::of)
                    .orElseGet(org.jawata.mcp.models.EngineNotes::defaultDir),
                org.osgi.framework.FrameworkUtil.getBundle(JawataEngine.class).getVersion().toString(), log);
            endpoint.onListening(note::ready);
            // The engine shares Eclipse's memory: the person is told once when it runs short (the signed response to that risk).
            memory = new MemoryNotice(person, MEMORY_NOTICE_FRACTION);
            memory.arm();
            EngineState.current().booted();
            engine = JawataEngine.assemble(host);
            hostRegistration = framework.registerService(EngineHost.class, engine.host(), null);
            whiteboardTracker = new WhiteboardTracker(framework, engine.whiteboard());
            whiteboardTracker.open();
            engine.start();
            // Eclipse owns the workspace, so there is no load to wait for: what it has open is what the engine answers about.
            EngineState.current().loadSucceeded();
            serving = new Thread(this::serve, "jawata engine");
            serving.setDaemon(true);
            serving.start();
        } catch (Exception | Error failure) {
            close();
            throw failure;
        }
    }

    private void serve() {
        try (Transport transport = new HttpTransport(engine.host().endpoint(), engine.host().lifecycle())) {
            engine.serve(transport);
        } catch (Exception e) {
            log.log(new Status(IStatus.ERROR, PLUGIN_ID, "The jawata engine stopped serving: " + e.getMessage(), e));
        }
    }

    /** Ends the engine and lets go of everything it was given; safe to call twice, and on an engine that never fully started. */
    @Override
    public synchronized void close() {
        if (memory != null) {
            memory.disarm();
            memory = null;
        }
        if (note != null) {
            note.close();
            note = null;
        }
        if (engine != null) {
            engine.stop();
            engine.whiteboard().stopping();
        }
        if (whiteboardTracker != null) {
            whiteboardTracker.close();
            whiteboardTracker = null;
        }
        if (hostRegistration != null) {
            hostRegistration.unregister();
            hostRegistration = null;
        }
        if (serving != null) {
            try {
                serving.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serving = null;
        }
        if (reconciler != null) {
            reconciler.close();
            reconciler = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        engine = null;
    }

    /** The engine, once {@link #start} has returned; empty before and after. */
    public synchronized Optional<JawataEngine> engine() {
        return Optional.ofNullable(engine);
    }
}
