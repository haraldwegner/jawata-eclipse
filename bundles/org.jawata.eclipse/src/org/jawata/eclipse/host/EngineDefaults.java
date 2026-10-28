package org.jawata.eclipse.host;

import java.nio.file.Path;
import java.util.Map;

import org.jawata.mcp.engine.SettingKeys;

/**
 * What the host answers for the engine's folder settings when the person has chosen none (contract §3.3: the plug-in's state area,
 * never the instance area, which in the IDE is the person's workspace folder). Without these the engine falls back to the instance
 * area and writes its working folders into the workspace the person keeps their projects in.
 */
final class EngineDefaults {

    private EngineDefaults() {
    }

    /**
     * @param stateArea the plug-in's state folder in the open workspace's metadata
     * @return the default for each setting that names a working folder: the workspace root the engine resolves its other folders from,
     *         the runtime folder and the coverage folder, all under {@code stateArea}
     */
    static Map<String, String> inStateArea(Path stateArea) {
        return Map.of(
            SettingKeys.WORKSPACE_ROOT.name(), stateArea.toString(),
            SettingKeys.RUNTIME_DIR.name(), stateArea.resolve("runtime").toString(),
            SettingKeys.COVERAGE_DIR.name(), stateArea.resolve("coverage").toString());
    }
}
