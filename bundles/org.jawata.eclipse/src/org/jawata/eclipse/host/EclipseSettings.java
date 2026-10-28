package org.jawata.eclipse.host;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.jawata.spi.host.EngineSettings;

/**
 * The engine's settings inside Eclipse (plan Stage 16, spec D5): every setting is an Eclipse preference, so the answer is the
 * preference when the person set one, else the property of that name given when Eclipse was started ({@code -D}, and the framework's
 * own such as {@code osgi.instance.area}, which the engine reads to find the workspace), else the host's own default (the folders
 * under the plug-in's state area, which the plug-in's preference initializer supplies). The person sets them on the one preference
 * page the plug-in adds (Stage 17).
 */
public final class EclipseSettings implements EngineSettings {

    private final IEclipsePreferences preferences;
    private final Map<String, String> defaults;
    private final UnaryOperator<String> frameworkProperty;

    /**
     * @param preferences       the plug-in's preference node, where the person's settings live
     * @param defaults          what the host answers when the person set nothing
     * @param frameworkProperty reads an OSGi framework property by name, or answers null
     */
    public EclipseSettings(IEclipsePreferences preferences, Map<String, String> defaults, UnaryOperator<String> frameworkProperty) {
        this.preferences = preferences;
        this.defaults = Map.copyOf(defaults);
        this.frameworkProperty = frameworkProperty;
    }

    @Override
    public Optional<String> get(String key) {
        String chosen = preferences.get(key, null);
        if (chosen != null) {
            return Optional.of(chosen);
        }
        String launched = frameworkProperty.apply(key);
        if (launched != null) {
            return Optional.of(launched);
        }
        return Optional.ofNullable(defaults.get(key));
    }
}
