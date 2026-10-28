package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 16, step 16.4: the engine starts with Eclipse's workbench by itself and an MCP client reaches it (contract §4.2, §4.3).
 * The test does not start an engine; it finds the one {@link EngineStartup} started, because a second engine in the same process
 * would fight the first for the store, and because "it runs unprompted" is exactly what is being proved.
 */
public class EngineRunsInEclipseTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> post(URI url, String session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void theEngineStartsWithTheWorkbenchAndAnswersOverMcp() throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());

        assertTrue(at.key().isEmpty(), "inside Eclipse the endpoint carries no key");
        assertEquals("127.0.0.1", at.url().getHost(), "and it listens on loopback only");

        HttpResponse<String> initialized = post(at.url(), null,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"eclipse-host-test\",\"version\":\"1\"}}}");
        assertEquals(200, initialized.statusCode(), initialized.body());
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElse(null);

        // The Java provider binds a moment after the engine starts; until it has, only the tools that name no language are listed.
        HttpResponse<String> tools = Await.found("the Java tools are listed", () -> {
            try {
                HttpResponse<String> listed = post(at.url(), session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
                return listed.body().contains("search_symbols") ? Optional.of(listed) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        });
        assertTrue(tools.body().contains("health_check"), "the engine lists health_check");

        HttpResponse<String> health = post(at.url(), session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"health_check\",\"arguments\":{}}}");
        assertEquals(200, health.statusCode(), health.body());
        assertFalse(health.body().contains("\"isError\":true"), "health_check answers without an error: " + health.body());
    }
}
