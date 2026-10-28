package org.jawata.eclipse.chat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

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

    private final IEclipsePreferences node = InstanceScope.INSTANCE.getNode(NODE);
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

    /**
     * What is wrong with an agent the person typed.
     *
     * @param text the agent as JSON text
     * @return the problem in words the person can act on, or empty when the text is blank (no agent) or a usable agent
     */
    public static Optional<String> problemWith(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode read = new ObjectMapper().readTree(trimmed);
            if (!read.isObject()) {
                return Optional.of("write it as one JSON object, such as " + "{\"command\": \"npx\", \"args\": [\"-y\", \"name\"]}.");
            }
            if (read.path("command").asText("").isBlank()) {
                return Optional.of("\"command\" is missing: name the program to start.");
            }
            if (read.has("args") && !read.get("args").isArray()) {
                return Optional.of("\"args\" must be a list of text, such as [\"-y\", \"name\"].");
            }
            if (read.has("env") && !read.get("env").isObject()) {
                return Optional.of("\"env\" must be an object of text values, such as {\"NAME\": \"value\"}.");
            }
            return Optional.empty();
        } catch (IOException unreadable) {
            String first = String.valueOf(unreadable.getMessage()).lines().findFirst().orElse("");
            return Optional.of("this is not valid JSON (" + first + ").");
        }
    }

    @Override
    public Runnable onChange(Runnable listener) {
        IEclipsePreferences.IPreferenceChangeListener told = event -> listener.run();
        node.addPreferenceChangeListener(told);
        return () -> node.removePreferenceChangeListener(told);
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
