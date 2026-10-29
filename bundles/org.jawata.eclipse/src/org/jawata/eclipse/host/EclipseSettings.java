package org.jawata.eclipse.host;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.spi.host.EngineSettings;

/**
 * The engine's settings inside Eclipse (plan Stage 16, spec D5): every setting is an Eclipse preference, so the answer is the
 * preference when the person set one, else the property of that name given when Eclipse was started ({@code -D}, and the framework's
 * own such as {@code osgi.instance.area}, which the engine reads to find the workspace), else the host's own default (the folders
 * under the plug-in's state area, which the plug-in's preference initializer supplies). The person sets them on the one preference
 * page the plug-in adds (Stage 17).
 */
public final class EclipseSettings implements EngineSettings {

    /**
     * The settings that are about the person's machine, not about one workspace: the shared store folder, the data home, the schema upgrade
     * and the folder of the engine notes. Eclipse keeps a workspace's preferences with that workspace, so these are kept in the
     * installation's configuration scope instead, where every workspace of the installation reads the same value.
     */
    public static final Set<String> MACHINE_WIDE = Set.of(SettingKeys.EXPERIENCE_SHARED_DIR.name(), SettingKeys.DATA_HOME.name(),
        SettingKeys.ALLOW_SCHEMA_UPGRADE.name(), SettingKeys.ENGINE_NOTES_DIR.name());

    private final IEclipsePreferences preferences;
    private final IEclipsePreferences machineWide;
    private final Map<String, String> defaults;
    private final UnaryOperator<String> frameworkProperty;

    /**
     * Settings with no separate place for the machine-wide ones: all of them are read from the one node.
     *
     * @param preferences       the plug-in's preference node, where the person's settings live
     * @param defaults          what the host answers when the person set nothing
     * @param frameworkProperty reads an OSGi framework property by name, or answers null
     */
    public EclipseSettings(IEclipsePreferences preferences, Map<String, String> defaults, UnaryOperator<String> frameworkProperty) {
        this(preferences, preferences, defaults, frameworkProperty);
    }

    /**
     * @param preferences       the plug-in's preference node for this workspace, where the person's settings live
     * @param machineWide       the plug-in's node in the installation's configuration scope, where {@link #MACHINE_WIDE} settings live
     * @param defaults          what the host answers when the person set nothing
     * @param frameworkProperty reads an OSGi framework property by name, or answers null
     */
    public EclipseSettings(IEclipsePreferences preferences, IEclipsePreferences machineWide, Map<String, String> defaults,
        UnaryOperator<String> frameworkProperty) {
        this.preferences = preferences;
        this.machineWide = machineWide;
        this.defaults = Map.copyOf(defaults);
        this.frameworkProperty = frameworkProperty;
    }

    @Override
    public Optional<String> get(String key) {
        if (MACHINE_WIDE.contains(key)) {
            String shared = machineWide.get(key, null);
            if (shared != null) {
                return Optional.of(shared);
            }
        }
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
