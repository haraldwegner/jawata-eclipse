package org.jawata.eclipse.tests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The MCP client the host tests talk to the engine inside Eclipse with: one session over the endpoint's URL, no key (the endpoint
 * inside Eclipse has none, contract §4.2). It speaks the same JSON-RPC over HTTP that studio and the agents do, so what a test sees
 * is what a client sees.
 */
final class McpClient {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI url;
    private final String session;
    private int nextId = 10;

    private McpClient(URI url, String session) {
        this.url = url;
        this.session = session;
    }

    /** Opens a session at the endpoint; fails the test with the engine's answer when it does not initialise. */
    static McpClient connect(URI url) throws Exception {
        HttpResponse<String> initialized = post(url, null,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"eclipse-host-test\",\"version\":\"1\"}}}");
        if (initialized.statusCode() != 200) {
            throw new IllegalStateException("the engine did not initialise: " + initialized.statusCode() + " " + initialized.body());
        }
        return new McpClient(url, initialized.headers().firstValue("Mcp-Session-Id").orElse(null));
    }

    private static HttpResponse<String> post(URI url, String session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The names of the tools the engine lists now; empty when the listing could not be read. */
    Optional<List<String>> toolNames() {
        try {
            HttpResponse<String> listed = post(url, session, "{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"tools/list\"}");
            JsonNode tools = payload(listed.body()).path("result").path("tools");
            return tools.isArray() ? Optional.of(tools.findValuesAsText("name")) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Calls a tool and returns the whole JSON-RPC answer, an error answer included. */
    JsonNode call(String tool, String argumentsJson) throws Exception {
        HttpResponse<String> answer = post(url, session, "{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}}");
        return payload(answer.body());
    }

    /** The text of a tool answer's first content item, which is the JSON the tool returned. */
    static String text(JsonNode answer) {
        return answer.path("result").path("content").path(0).path("text").asText("");
    }

    /** Whether the tool answered with its error flag set. */
    static boolean failed(JsonNode answer) {
        return answer.path("result").path("isError").asBoolean(false) || answer.has("error");
    }

    // The endpoint answers JSON, or one event-stream frame carrying the JSON; both are one object.
    private static JsonNode payload(String body) throws Exception {
        String json = body.startsWith("event:") || body.startsWith("data:")
            ? body.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).trim()).findFirst().orElse("{}")
            : body;
        return JSON.readTree(json);
    }
}
