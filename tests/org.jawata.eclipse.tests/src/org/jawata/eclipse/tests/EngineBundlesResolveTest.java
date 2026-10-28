package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Plan Stage 15 (spec D2, contract R8): the released engine's bundles, and the two libraries they bring, resolve on THIS Eclipse.
 * The bundles named below are the plug-ins of {@code features/org.jawata.engine.feature/feature.xml}; the end-to-end script reads that
 * file as its one list, and this test repeats it only because a running Eclipse has no feature.xml to read.
 */
public class EngineBundlesResolveTest {

    private static final List<String> ENGINE_BUNDLES = List.of(
        "org.jawata.spi", "org.jawata.core", "org.jawata.mcp", "org.jawata.catalogue",
        "com.h2database",
        "com.fasterxml.jackson.core.jackson-annotations", "com.fasterxml.jackson.core.jackson-core",
        "com.fasterxml.jackson.core.jackson-databind");

    private static Bundle bundle(String symbolicName) {
        Bundle self = FrameworkUtil.getBundle(EngineBundlesResolveTest.class);
        for (Bundle b : self.getBundleContext().getBundles()) {
            if (symbolicName.equals(b.getSymbolicName())) {
                return b;
            }
        }
        return null;
    }

    @Test
    public void everyEngineBundleIsInstalledAndResolves() {
        FrameworkWiring framework = FrameworkUtil.getBundle(EngineBundlesResolveTest.class)
            .getBundleContext().getBundle(0).adapt(FrameworkWiring.class);
        for (String name : ENGINE_BUNDLES) {
            Bundle b = bundle(name);
            assertNotNull(b, name + " is not installed in this Eclipse");
            assertTrue(framework.resolveBundles(List.of(b)) && b.getState() >= Bundle.RESOLVED,
                name + " did not resolve; its state is " + b.getState());
        }
    }

    @Test
    public void theEngineCoreIsWiredToEclipsesOwnJdt() {
        Bundle core = bundle("org.jawata.core");
        assertNotNull(core, "org.jawata.core is not installed in this Eclipse");
        BundleWiring wiring = core.adapt(BundleWiring.class);
        assertNotNull(wiring, "org.jawata.core has no wiring: it is not resolved");
        List<BundleWire> wires = wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
        String jdtProvider = null;
        for (BundleWire w : wires) {
            Object pkg = w.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
            if ("org.eclipse.jdt.core.dom".equals(pkg)) {
                jdtProvider = w.getProvider().getSymbolicName();
            }
        }
        assertEquals("org.eclipse.jdt.core", jdtProvider,
            "org.jawata.core must reach org.eclipse.jdt.core.dom through Eclipse's own org.eclipse.jdt.core bundle");
    }
}
