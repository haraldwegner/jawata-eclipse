package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 16, step 16.4: the engine starts with Eclipse's workbench by itself and an MCP client reaches it (contract §4.2, §4.3).
 * The test does not start an engine; it finds the one {@link EngineStartup} started, because a second engine in the same process
 * would fight the first for the store, and because "it runs unprompted" is exactly what is being proved.
 */
public class EngineRunsInEclipseTest {

    @Test
    public void theEngineStartsWithTheWorkbenchAndAnswersOverMcp() throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());

        assertTrue(at.key().isEmpty(), "inside Eclipse the endpoint carries no key");
        assertEquals("127.0.0.1", at.url().getHost(), "and it listens on loopback only");

        McpClient client = McpClient.connect(at.url());
        // The Java provider binds a moment after the engine starts; until it has, only the tools that name no language are listed.
        var tools = Await.found("the Java tools are listed", () -> client.toolNames().filter(names -> names.contains("search_symbols")));
        assertTrue(tools.contains("health_check"), "the engine lists health_check");

        JsonNode health = client.call("health_check", "{}");
        assertFalse(McpClient.failed(health), "health_check answers without an error: " + health);
    }
}
