package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 21, step 21.4: chat-core's own host-port checks (what a host must do about spawning, ending and telling an agent's end, what
 * it answers at once, what a notifier does), run in the Eclipse page against the real adapter and the real Java relay behind it. The
 * checks are chat-core's, bundled with the adapter by {@code scripts/build-chat-page.sh} into {@code .work/chat-conformance/}; a host that
 * fails one of them loses an agent's death or a settings read without anyone noticing.
 */
public class ChatHostConformanceTest {

    @Test
    public void theEclipseAdapterPassesChatCoresHostPortChecks() throws Exception {
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent())) {
            page.run("window.runConformance({\"running\": {\"command\": \"node\", \"args\": [\"-e\", \"setInterval(() => {}, 1000);\"]},"
                + " \"cannotStart\": {\"command\": \"jawata-no-such-agent-program\"}});");

            JsonNode result = page.waitForCall("conformance");
            List<String> failures = new ArrayList<>();
            result.path("failures").forEach(failure -> failures.add(failure.asText()));
            assertEquals(List.of(), failures, "what the Eclipse host does not do that chat-core says a host must");
            assertTrue(page.callWith("spawn").isPresent(), "and the checks did start agents through Java: " + page.calls().size() + " calls");
        }
    }
}
