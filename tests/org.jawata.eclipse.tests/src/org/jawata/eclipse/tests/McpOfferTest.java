package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jawata.eclipse.access.EngineAddress;
import org.jawata.eclipse.chat.AgentRelay;
import org.jawata.eclipse.chat.McpOffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 21, step 21.12: the agent is offered jawata's tools. The offer adds the engine inside this Eclipse, as an HTTP server named
 * "jawata", to the three requests that name MCP servers and touches nothing else; and an agent that reads what it was offered can call a
 * jawata tool at that address, with no key.
 */
public class McpOfferTest {

    private static final String URL = "http://127.0.0.1:4711/mcp";

    private final ObjectMapper json = new ObjectMapper();
    private final McpOffer offer = new McpOffer(() -> Optional.of(URL));
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private AgentRelay relay;

    @AfterEach
    public void endAgent() {
        if (relay != null) {
            relay.close();
        }
    }

    private JsonNode servers(String request) throws Exception {
        return json.readTree(offer.apply(request)).path("params").path("mcpServers");
    }

    @Test
    public void theEngineIsAddedToTheThreeRequestsThatNameMcpServersAndKeepsTheOnesThereAlready() throws Exception {
        for (String method : List.of("session/new", "session/load", "session/resume")) {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"" + method + "\",\"params\":{\"cwd\":\"/w\",\"mcpServers\":[]}}";
            JsonNode offered = servers(request);
            assertEquals(1, offered.size(), method + ": one server is offered: " + offered);
            assertEquals("http", offered.get(0).path("type").asText(), method);
            assertEquals("jawata", offered.get(0).path("name").asText(), method);
            assertEquals(URL, offered.get(0).path("url").asText(), method);
            assertEquals(0, offered.get(0).path("headers").size(), method + ": and no key");
        }
        String withOther = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\","
            + "\"mcpServers\":[{\"type\":\"http\",\"name\":\"other\",\"url\":\"http://x/mcp\",\"headers\":[]}]}}";
        assertEquals(List.of("other", "jawata"), servers(withOther).findValuesAsText("name"), "the person's own servers stay, in front");
        String offeredTwice = offer.apply(offer.apply(withOther));
        assertEquals(List.of("other", "jawata"), json.readTree(offeredTwice).path("params").path("mcpServers").findValuesAsText("name"), "and the offer is never made twice");
    }

    @Test
    public void everyOtherMessageAndARequestWithoutAnAddressPassesUnchanged() throws Exception {
        List<String> others = List.of(
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s\",\"prompt\":[{\"type\":\"text\",\"text\":\"what does session/new do?\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow\"}}}",
            "not json at all, but it mentions \"session/new\"");
        for (String other : others) {
            assertEquals(other, offer.apply(other), "unchanged byte for byte");
        }
        String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\",\"mcpServers\":[]}}";
        assertEquals(request, new McpOffer(Optional::empty).apply(request), "until the engine has an address the request is sent as it is");
    }

    @Test
    public void anAgentThatReadsTheOfferCallsAJawataToolAtTheEnginesAddress() throws Exception {
        String address = Await.found("the engine's address", EngineAddress::url);
        // a stand-in agent: on session/new it reads the HTTP server it was offered, opens an MCP session there with no key, calls health_check,
        // and answers the request with what it was offered and what the tool said
        String script = "const readline = require('readline');"
            + "readline.createInterface({ input: process.stdin }).on('line', async (line) => {"
            + "  const msg = JSON.parse(line);"
            + "  if (msg.method !== 'session/new') { return; }"
            + "  const servers = msg.params.mcpServers;"
            + "  const http = servers.find((s) => s.type === 'http');"
            + "  let health = null;"
            + "  if (http) {"
            + "    const post = (body, session) => fetch(http.url, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json',"
            + "      'Accept': 'application/json, text/event-stream' }, session ? { 'Mcp-Session-Id': session } : {}), body: JSON.stringify(body) });"
            + "    const init = await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {},"
            + "      clientInfo: { name: 'stand-in agent', version: '1' } } });"
            + "    const session = init.headers.get('mcp-session-id'); await init.text();"
            + "    const call = await post({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'health_check', arguments: {} } }, session);"
            + "    health = await call.text();"
            + "  }"
            + "  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: msg.id, result: { sessionId: 's', _meta: { servers, health } } }) + '\\n');"
            + "});";
        relay = new AgentRelay(new AgentRelay.Listener() {
            @Override
            public void onMessage(String agentId, String message) {
                messages.add(message);
            }

            @Override
            public void onClosed(String agentId, Integer code) {
            }

            @Override
            public void onError(String agentId, String message) {
                messages.add("ERROR " + message);
            }
        }, line -> { });
        String agent = relay.start("agent_1", List.of("node", "-e", script), Map.of(), null);

        relay.send(agent, new McpOffer(EngineAddress::url).apply("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\",\"mcpServers\":[]}}"));

        JsonNode answer = json.readTree(Await.found("the stand-in agent's answer", () -> messages.stream().findFirst()));
        JsonNode meta = answer.path("result").path("_meta");
        assertEquals(address, meta.path("servers").get(0).path("url").asText(), "it was offered the engine's address: " + answer);
        String health = meta.path("health").asText();
        assertTrue(health.contains("success"), "and a jawata tool answered at that address: " + health);
        assertFalse(health.contains("\"error\""), "without an error: " + health);
    }
}
