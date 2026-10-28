package org.jawata.eclipse.host;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
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
        java.nio.file.Path stateArea = Platform.getStateLocation(FrameworkUtil.getBundle(EnginePreferenceInitializer.class)).toFile().toPath();
        EngineDefaults.inStateArea(stateArea).forEach(defaults::put);
    }
}
