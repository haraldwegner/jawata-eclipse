package org.jawata.eclipse.host;

import java.io.IOException;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.jawata.mcp.engine.SettingKeys;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Eclipse's own way to say what a preference is when the person set none (plan Stage 17, spec D5): the engine's working folders
 * default to the plug-in's state area. Eclipse runs this the first time the plug-in's default preferences are read, so the defaults
 * live in the preference store and the settings the engine reads come from one store (architecture §12 refusal 5).
 */
public final class EnginePreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(EclipseEngine.PLUGIN_ID);
        Bundle bundle = FrameworkUtil.getBundle(EnginePreferenceInitializer.class);
        java.nio.file.Path stateArea = Platform.getStateLocation(bundle).toFile().toPath();
        EngineDefaults.inStateArea(stateArea).forEach(defaults::put);
        // the runner tools the plug-in carries, as files: without them the engine refuses run_tests, coverage and mutation inside Eclipse
        try {
            BundledTools.installedAt(bundle, stateArea.resolve("tools")).ifPresent(tools -> defaults.put(SettingKeys.TOOLS_DIR.name(), tools.toString()));
        } catch (IOException e) {
            Platform.getLog(bundle).log(Status.warning("jawata: the runner tools could not be put on disk; run_tests will be refused", e));
        }
    }
}
