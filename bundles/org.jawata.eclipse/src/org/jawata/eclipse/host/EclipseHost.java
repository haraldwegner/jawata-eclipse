package org.jawata.eclipse.host;

import java.util.List;
import java.util.Optional;

import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.EngineLifecycle;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.Instruments;
import org.jawata.spi.host.LanguageBinding;
import org.jawata.spi.host.PersonChannel;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;

/**
 * The host of an engine that runs inside Eclipse (plan Stage 16, step 16.4; contract §2.3, Eclipse column): the open workspace, the
 * address it listens on, the preferences it reads, the status the person sees, and the Java binding over the Java model Eclipse
 * already holds. On the read side Eclipse owns its workspace, runs nothing on the engine's behalf and checks no unsaved text, and
 * says so in its capabilities; the later stages that add writes, runs and debugging change those answers here and nowhere else.
 */
public final class EclipseHost implements EngineHost {

    private final HostWorkspace workspace;
    private final EngineLifecycle lifecycle;
    private final EndpointPolicy endpoint;
    private final EngineSettings settings;
    private final EngineDirectories directories;
    private final LanguageBinding javaBinding;
    private final Reconciler reconciler;
    private final WorkspaceWrites writes;
    private final PersonChannel person;
    private final HostCapabilities capabilities;

    /**
     * @param workspace   the projects Eclipse has open, which the engine follows and never changes
     * @param lifecycle   what a listener failure means for Eclipse: the person is told, Eclipse goes on
     * @param endpoint    where to listen
     * @param settings    the person's preferences, else the host's defaults
     * @param directories the folders the engine keeps its files in
     * @param javaBinding what Eclipse supplies to the Java provider
     * @param reconciler  how the workspace is brought up to date with the disk and built
     * @param writes      how a change lands in the workspace
     * @param person      how the engine tells the person something
     */
    public EclipseHost(HostWorkspace workspace, EngineLifecycle lifecycle, EndpointPolicy endpoint, EngineSettings settings,
                       EngineDirectories directories, LanguageBinding javaBinding, Reconciler reconciler, WorkspaceWrites writes,
                       PersonChannel person) {
        this.workspace = workspace;
        this.lifecycle = lifecycle;
        this.endpoint = endpoint;
        this.settings = settings;
        this.directories = directories;
        this.javaBinding = javaBinding;
        this.reconciler = reconciler;
        this.writes = writes;
        this.person = person;
        this.capabilities = new HostCapabilities("eclipse", false, false, false, List.of("java"));
    }

    @Override
    public PersonChannel person() {
        return person;
    }

    @Override
    public Instruments instruments() {
        return Instruments.NONE;
    }

    @Override
    public HostCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Reconciler reconciler() {
        return reconciler;
    }

    @Override
    public WorkspaceWrites writes() {
        return writes;
    }

    @Override
    public HostWorkspace workspace() {
        return workspace;
    }

    @Override
    public EngineLifecycle lifecycle() {
        return lifecycle;
    }

    @Override
    public EndpointPolicy endpoint() {
        return endpoint;
    }

    @Override
    public EngineSettings settings() {
        return settings;
    }

    @Override
    public EngineDirectories directories() {
        return directories;
    }

    @Override
    public <B extends LanguageBinding> Optional<B> binding(Class<B> kind) {
        return kind.isInstance(javaBinding) ? Optional.of(kind.cast(javaBinding)) : Optional.empty();
    }
}
