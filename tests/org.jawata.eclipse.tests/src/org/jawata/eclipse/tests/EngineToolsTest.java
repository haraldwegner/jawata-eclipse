package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EnginePreferenceInitializer;
import org.jawata.mcp.engine.SettingKeys;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Plan Stage 26 (found by the dogfood of 2026-10-30): {@code run_tests}, coverage and mutation start a forked JVM from the runner jars of
 * the engine release, which the engine finds through the setting {@code jawata.tools.dir}. The headless engine finds them beside its own
 * bundles; inside Eclipse nothing does, so the plug-in carries them (its {@code tools/} folder, put there by {@code scripts/fetch-engine.sh})
 * and answers that setting with a folder on disk that holds them.
 */
public class EngineToolsTest {

    private static Path toolsFolder() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(EclipseEngine.PLUGIN_ID);
        String folder = defaults.get(SettingKeys.TOOLS_DIR.name(), null);
        assertNotNull(folder, "the plug-in answers a default for " + SettingKeys.TOOLS_DIR.name()
            + " (scripts/fetch-engine.sh puts the release's tools/ folder into the plug-in)");
        return Path.of(folder);
    }

    private static List<String> jarNames(Path folder) throws Exception {
        try (Stream<Path> files = Files.list(folder)) {
            List<String> names = new ArrayList<>(files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".jar")).toList());
            Collections.sort(names);
            return names;
        }
    }

    @Test
    public void theSettingNamesAFolderHoldingTheRunnerAndItsLauncherByteForByte() throws Exception {
        Path folder = toolsFolder();
        assertTrue(Files.isDirectory(folder), "the folder exists: " + folder);
        List<String> names = jarNames(folder);
        assertTrue(names.stream().anyMatch(n -> n.startsWith("org.jawata.testrunner-")), "the test runner is there: " + names);
        assertTrue(names.stream().anyMatch(n -> n.startsWith("junit-platform-console-standalone-")), "JUnit's launcher is there: " + names);
        Bundle bundle = FrameworkUtil.getBundle(EnginePreferenceInitializer.class);
        List<URL> carried = Collections.list(bundle.findEntries("tools", "*.jar", false));
        assertEquals(carried.size(), names.size(), "every jar the plug-in carries is in the folder");
        for (URL entry : carried) {
            String name = entry.getPath().substring(entry.getPath().lastIndexOf('/') + 1);
            try (var in = entry.openStream()) {
                assertTrue(java.util.Arrays.equals(in.readAllBytes(), Files.readAllBytes(folder.resolve(name))), name + " is the plug-in's own copy");
            }
        }
    }

    @Test
    public void aDamagedJarIsPutBackWhenTheDefaultsAreReadAgain() throws Exception {
        Path folder = toolsFolder();
        Path runner = folder.resolve(jarNames(folder).stream().filter(n -> n.startsWith("org.jawata.testrunner-")).findFirst().orElseThrow());
        byte[] whole = Files.readAllBytes(runner);
        Files.write(runner, new byte[] { 1, 2, 3 });
        new EnginePreferenceInitializer().initializeDefaultPreferences();
        assertTrue(java.util.Arrays.equals(whole, Files.readAllBytes(runner)), "the damaged runner jar was replaced by the plug-in's copy");
    }
}
