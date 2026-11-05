package org.jawata.eclipse.host;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * jawata's agent as the chat in Eclipse starts it (plan Stage 25a.4, Sprint 28h D1). The agent is a second face of the engine and answers on
 * the route {@value #ROUTE} of the engine's own listener, which the engine inside this Eclipse already runs; what a chat starts is the
 * engine's small bridge program ({@code java -jar jawata.jar --acp --url <listener>}), which carries the chat's lines to that route and the
 * answers back and holds no agent logic. The listener inside Eclipse has no key (contract section 4.2), so the bridge is given none. The
 * person sets nothing up: the bridge's jar is a file of this plug-in, and the listener's address is the engine's own.
 */
public final class AgentBridge {

    /** The plug-in's folder holding the bridge's jar, put there by {@code scripts/fetch-engine.sh}. */
    static final String ENTRY = "agent";

    /** The bridge's jar, as the release names it. */
    static final String JAR = "jawata.jar";

    /** The route of the listener the engine's agent face answers on. */
    public static final String ROUTE = "/acp";

    private static final String MCP_ROUTE = "/mcp";

    private AgentBridge() {
    }

    /**
     * The command that starts the bridge.
     *
     * @param java   the Java program to run it with
     * @param jar    the bridge's jar on disk
     * @param mcpUrl the engine's {@code /mcp} address, from which the listener's base address is taken
     * @return the program and its arguments
     */
    public static List<String> commandFor(Path java, Path jar, URI mcpUrl) {
        String text = mcpUrl.toString();
        String base = text.endsWith(MCP_ROUTE) ? text.substring(0, text.length() - MCP_ROUTE.length()) : text;
        return List.of(java.toString(), "-jar", jar.toString(), "--acp", "--url", base);
    }

    /**
     * The command that starts the bridge for the engine inside this Eclipse: the Java Eclipse itself runs on, the jar this plug-in carries
     * (put on disk, replacing a damaged copy) and the address the listener is at now.
     *
     * @param mcpUrl the engine's {@code /mcp} address
     * @return the command, or empty when this build of the plug-in carries no bridge
     * @throws IOException when the jar cannot be put on disk
     */
    public static Optional<List<String>> forEngineAt(URI mcpUrl) throws IOException {
        Bundle bundle = FrameworkUtil.getBundle(AgentBridge.class);
        Path state = Platform.getStateLocation(bundle).toFile().toPath().resolve(ENTRY);
        Optional<Path> folder = BundledTools.installedAt(bundle, ENTRY, state);
        if (folder.isEmpty() || !folder.get().resolve(JAR).toFile().isFile()) {
            return Optional.empty();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return Optional.of(commandFor(java, folder.get().resolve(JAR), mcpUrl));
    }
}
