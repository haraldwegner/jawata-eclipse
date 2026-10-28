package org.jawata.eclipse.chat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.prefs.Preferences;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The chat's setup read from the workspace's preferences (node {@value #NODE}): the agent as one JSON object
 * ({@code {"command": "npx", "args": ["-y", "..."], "env": {"NAME": "value"}}}) under {@value #AGENT}, and the folder it works in under
 * {@value #WORKING_FOLDER} (the workspace folder when not set). The preference page that edits them is step 21.8.
 */
public final class PreferencesChatSettings implements ChatSettings {

    /** The preference node the chat's keys are under. */
    public static final String NODE = "org.jawata.eclipse.chat";
    /** The key holding the agent as a JSON object. */
    public static final String AGENT = "agent";
    /** The key holding the folder the agent works in. */
    public static final String WORKING_FOLDER = "workingFolder";

    private final Preferences node = InstanceScope.INSTANCE.getNode(NODE);
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode agent() {
        String text = node.get(AGENT, "").trim();
        if (text.isEmpty()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(text);
        } catch (IOException unreadable) {
            return json.createObjectNode();
        }
    }

    @Override
    public List<String> agentCommand() {
        JsonNode agent = agent();
        List<String> command = new ArrayList<>();
        String program = agent.path("command").asText("").trim();
        if (program.isEmpty()) {
            return command;
        }
        command.add(program);
        agent.path("args").forEach(argument -> command.add(argument.asText()));
        return command;
    }

    @Override
    public Map<String, String> agentEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        agent().path("env").fields().forEachRemaining(entry -> environment.put(entry.getKey(), entry.getValue().asText()));
        return environment;
    }

    @Override
    public Path workingFolder() {
        String set = node.get(WORKING_FOLDER, "").trim();
        if (!set.isEmpty()) {
            return Path.of(set);
        }
        try {
            return Path.of(Platform.getInstanceLocation().getURL().toURI());
        } catch (URISyntaxException | RuntimeException unavailable) {
            return Path.of(System.getProperty("user.home"));
        }
    }

    @Override
    public String agentSetup() {
        return "the jawata chat preferences";
    }

    @Override
    public String clientVersion() {
        Bundle bundle = FrameworkUtil.getBundle(PreferencesChatSettings.class);
        return bundle == null ? "0" : bundle.getVersion().toString();
    }
}
