package org.jawata.eclipse.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.jawata.core.ModelSource;
import org.jawata.eclipse.host.EclipseDirectories;
import org.jawata.eclipse.host.EclipseEndpointPolicy;
import org.jawata.eclipse.host.EclipseEngineLifecycle;
import org.jawata.eclipse.host.EclipseHost;
import org.jawata.eclipse.host.EclipsePersonChannel;
import org.jawata.eclipse.host.EclipseReconciler;
import org.jawata.eclipse.host.EclipseSettings;
import org.jawata.eclipse.host.EclipseWorkspace;
import org.jawata.eclipse.host.EclipseWrites;
import org.jawata.eclipse.java.EclipseJavaBinding;
import org.jawata.eclipse.java.EclipseJdtService;
import org.jawata.eclipse.java.EclipseModelSource;
import org.jawata.eclipse.run.EclipseDebugTurn;
import org.jawata.mcp.contract.BuildVerdictContract;
import org.jawata.mcp.contract.ChangePathsContract;
import org.jawata.mcp.contract.DebugTurnContract;
import org.jawata.mcp.contract.EndpointPolicyContract;
import org.jawata.mcp.contract.EngineLifecycleContract;
import org.jawata.mcp.contract.HostCapabilitiesContract;
import org.jawata.mcp.contract.HostWorkspaceContract;
import org.jawata.mcp.contract.LoadedWorkspaceContract;
import org.jawata.mcp.contract.PersonChannelContract;
import org.jawata.mcp.contract.ReconcilerContract;
import org.jawata.mcp.contract.SymbolAnchorsContract;
import org.jawata.mcp.contract.TestRuntimeContract;
import org.jawata.mcp.contract.WorkspaceWritesContract;
import org.jawata.mcp.engine.JawataEngine;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.DebugTurn;
import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.Notice;
import org.jawata.spi.host.PersonChannel;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.LoadedWorkspace;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.osgi.framework.FrameworkUtil;

/**
 * Plan 25a.1 (A16-9): the Stage 8 contract tests of the engine's release (contract §18 ring 1), run against EclipseHost's ports and against the
 * Java binding, inside a real Eclipse. The contract classes are the engine's own, from the test-support jar its release tag carries
 * (scripts/fetch-engine.sh puts it on this bundle's class path); this class only says how to get each port from Eclipse. The headless host runs
 * the same contracts in the engine's own build, so a port behaves the same wherever it comes from.
 */
public class EclipseHostContractTest {

    private static final IWorkspace WORKSPACE = ResourcesPlugin.getWorkspace();
    private static final IWorkspaceRoot ROOT = WORKSPACE.getRoot();
    private static final String PLUGIN = "org.jawata.eclipse";
    private static final ILog LOG = ILog.of(FrameworkUtil.getBundle(EclipseHostContractTest.class));

    /** What the engine told the person, in order, so a test can say how the host was told. */
    private static final class Told implements PersonChannel {
        final List<Notice> notices = new CopyOnWriteArrayList<>();

        @Override
        public void tell(Notice notice) {
            notices.add(notice);
        }

        @Override
        public Activity begin(String label) {
            return PersonChannel.SILENT.begin(label);
        }
    }

    /** The ports of an EclipseHost, built as the workbench builds them, and let go of when the test is done. */
    private static final class Rig implements AutoCloseable {
        final EclipseWorkspace workspace = new EclipseWorkspace(ROOT);
        final EclipseEndpointPolicy endpoint = new EclipseEndpointPolicy();
        final EclipsePersonChannel person = new EclipsePersonChannel(LOG, PLUGIN);
        final EclipseReconciler reconciler = new EclipseReconciler(WORKSPACE);
        final EclipseDebugTurn turn = new EclipseDebugTurn(DebugPlugin.getDefault().getLaunchManager(), LOG, PLUGIN);
        final EclipseJdtService model = new EclipseJdtService(ROOT, 30);
        final EclipseHost host;
        private final IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode("org.jawata.eclipse.tests.contract." + System.nanoTime());

        Rig() {
            EclipseSettings settings = new EclipseSettings(preferences, java.util.Map.of(), key -> null);
            host = new EclipseHost(workspace, new EclipseEngineLifecycle(LOG, new Told(), PLUGIN), endpoint, settings,
                new EclipseDirectories(settings, List::of), new EclipseJavaBinding(new EclipseModelSource(model, PLUGIN)), reconciler,
                new EclipseWrites(PLUGIN), person, () -> turn);
        }

        @Override
        public void close() {
            turn.close();
            reconciler.close();
            model.close();
            try {
                preferences.removeNode();
            } catch (org.osgi.service.prefs.BackingStoreException e) {
                // a scratch preference node that cannot be removed is left to the workspace's own cleanup
            }
        }
    }

    /** The Java provider the engine's Java binding serves, built as the engine builds it over Eclipse's model; its class is the engine's own, not exported. */
    private static LanguageProvider javaProvider(ModelSource model, HostWorkspace workspace) throws Exception {
        Class<?> type = JawataEngine.class.getClassLoader().loadClass("org.jawata.mcp.java.JavaProvider");
        return (LanguageProvider) type.getConstructor(ModelSource.class, HostWorkspace.class).newInstance(model, workspace);
    }

    private static List<String> namesInWorkspace() {
        List<String> names = new ArrayList<>();
        for (IProject project : ROOT.getProjects()) {
            names.add(project.getName());
        }
        return names;
    }

    // ---- the host's ports ----

    @Nested
    class Workspace extends HostWorkspaceContract {
        @Override
        protected HostWorkspace workspace() {
            return new EclipseWorkspace(ROOT);
        }

        @Override
        protected Set<String> expectedFolders() {
            return Set.copyOf(namesInWorkspace());
        }
    }

    @Nested
    class Reconciliation extends ReconcilerContract {
        private Rig rig;

        @Override
        protected Reconciler reconciler() {
            rig = new Rig();
            return rig.reconciler;
        }

        @AfterEach
        void letGo() {
            if (rig != null) {
                rig.close();
            }
        }
    }

    @Nested
    class Writes extends WorkspaceWritesContract {
        @Override
        protected WorkspaceWrites writes() {
            return new EclipseWrites(PLUGIN);
        }
    }

    @Nested
    class Lifecycle extends EngineLifecycleContract {
        private Told told;

        @Override
        protected org.jawata.spi.host.EngineLifecycle lifecycle() {
            told = new Told();
            return new EclipseEngineLifecycle(LOG, told, PLUGIN);
        }

        @Override
        protected List<String> reportsSeen() {
            return told.notices.stream().map(Notice::text).toList();
        }
    }

    @Nested
    class Person extends PersonChannelContract {
        @Override
        protected PersonChannel channel() {
            return new EclipsePersonChannel(LOG, PLUGIN);
        }
    }

    @Nested
    class Endpoint extends EndpointPolicyContract {
        @Override
        protected EndpointPolicy policy() {
            return new EclipseEndpointPolicy();
        }
    }

    @Nested
    class Capabilities extends HostCapabilitiesContract {
        private Rig rig;

        /** One host per test, however the contract asks: its capabilities and its workspace are the same host's. */
        private Rig host() {
            if (rig == null) {
                rig = new Rig();
            }
            return rig;
        }

        @Override
        protected HostCapabilities capabilities() {
            return host().host.capabilities();
        }

        @Override
        protected HostWorkspace workspaceOfTheSameHost() {
            return host().host.workspace();
        }

        @AfterEach
        void letGo() {
            if (rig != null) {
                rig.close();
            }
        }
    }

    @Nested
    class Turn extends DebugTurnContract {
        private Rig rig;
        private final ILaunchManager launches = DebugPlugin.getDefault().getLaunchManager();
        private Launch persons;

        @Override
        protected DebugTurn turn() {
            rig = new Rig();
            return rig.host.instruments().debugTurn();
        }

        /** The person's own debug launch, as Eclipse's Debug adds one: a launch in debug mode that has not ended. */
        @Override
        protected boolean personStartsDebugging() {
            persons = new Launch(null, ILaunchManager.DEBUG_MODE, null) {
                @Override
                public boolean isTerminated() {
                    return false;
                }
            };
            launches.addLaunch(persons);
            return true;
        }

        @AfterEach
        void letGo() {
            if (persons != null) {
                launches.removeLaunch(persons);
            }
            if (rig != null) {
                rig.close();
            }
        }
    }

    // ---- the Java binding, over a Java project in the workspace Eclipse runs these in ----

    /** A Java project in the workspace, the engine's Java provider over Eclipse's model of it, and what the provider is handed by the host. */
    private static final class JavaRig implements AutoCloseable {
        final IProject project;
        final EclipseJdtService service = new EclipseJdtService(ROOT, 30);
        final EclipseReconciler reconciler = new EclipseReconciler(WORKSPACE);
        final LanguageProvider provider;
        final ProviderContext context;

        JavaRig() throws Exception {
            project = WorkspaceProjects.java("jawata-contract-");
            provider = javaProvider(new EclipseModelSource(service, PLUGIN), new EclipseWorkspace(ROOT));
            EclipseWrites writes = new EclipseWrites(PLUGIN);
            context = new ProviderContext() {
                @Override
                public Reconciler reconciler() {
                    return reconciler;
                }

                @Override
                public WorkspaceWrites writes() {
                    return writes;
                }
            };
        }

        Path root() {
            return Path.of(project.getLocation().toOSString());
        }

        @Override
        public void close() {
            reconciler.close();
            service.close();
            WorkspaceProjects.deleteQuietly(project);
        }
    }

    /** The Java contracts below make a rig before each test and let go of it after. */
    private abstract static class OverJava {
        JavaRig rig;

        @BeforeEach
        void aJavaProjectIsOpen() throws Exception {
            rig = new JavaRig();
        }

        @AfterEach
        void theProjectIsRemoved() {
            if (rig != null) {
                rig.close();
            }
        }
    }

    @Nested
    class Anchors extends SymbolAnchorsContract {
        private final OverJava java = new OverJava() { };

        @BeforeEach
        void open() throws Exception {
            java.aJavaProjectIsOpen();
        }

        @AfterEach
        void close() {
            java.theProjectIsRemoved();
        }

        @Override
        protected SymbolAnchors anchors() {
            return java.rig.provider.anchors(java.rig.context);
        }

        @Override
        protected Optional<String> resolvableSymbol() {
            return Optional.of("p.Greeter");
        }
    }

    @Nested
    class JavaWorkspace extends LoadedWorkspaceContract {
        private final OverJava java = new OverJava() { };

        @BeforeEach
        void open() throws Exception {
            java.aJavaProjectIsOpen();
        }

        @AfterEach
        void close() {
            java.theProjectIsRemoved();
        }

        @Override
        protected LoadedWorkspace workspace() {
            return java.rig.provider.workspace(java.rig.context).orElseThrow();
        }

        @Override
        protected Set<String> expectedKeys() {
            return Set.copyOf(java.rig.service.projectKeys());
        }
    }

    @Nested
    class Paths extends ChangePathsContract {
        private final OverJava java = new OverJava() { };

        @BeforeEach
        void open() throws Exception {
            java.aJavaProjectIsOpen();
        }

        @AfterEach
        void close() {
            java.theProjectIsRemoved();
        }

        @Override
        protected ChangePaths paths() {
            return java.rig.provider.paths(java.rig.context);
        }
    }

    @Nested
    class Verdict extends BuildVerdictContract {
        private final OverJava java = new OverJava() { };
        private Path broken;

        @BeforeEach
        void open() throws Exception {
            java.aJavaProjectIsOpen();
        }

        @AfterEach
        void close() {
            java.theProjectIsRemoved();
        }

        @Override
        protected BuildVerdict verdict() throws Exception {
            if (broken == null) {
                broken = java.rig.root().resolve("src/p/BrokenMain.java");
                Files.writeString(broken, "package p;\npublic class BrokenMain {\n    void broken() {\n        int x = ;\n    }\n}\n");
                java.rig.reconciler.syncBeforeCall();
                java.rig.reconciler.rebuild(BuildScope.of(java.rig.project.getName()), BuildKind.FULL, new NullProgressMonitor());
            }
            return java.rig.provider.verdict(java.rig.context);
        }

        @Override
        protected String fileThatBuilds() {
            return java.rig.root().resolve("src/p/Greeter.java").toString();
        }

        @Override
        protected String fileThatDoesNotBuild() throws Exception {
            verdict();
            return broken.toString();
        }
    }

    @Nested
    class Tests extends TestRuntimeContract {
        private final OverJava java = new OverJava() { };

        @BeforeEach
        void open() throws Exception {
            java.aJavaProjectIsOpen();
        }

        @AfterEach
        void close() {
            java.theProjectIsRemoved();
        }

        @Override
        protected TestRuntime runtime() {
            return java.rig.provider.tests(java.rig.context).orElseThrow();
        }
    }
}
