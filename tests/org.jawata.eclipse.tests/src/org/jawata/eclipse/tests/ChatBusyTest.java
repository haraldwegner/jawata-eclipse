package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 21, step 21.14: while the agent is in the middle of a long tool call the chat stays usable. A stand-in agent answers the first
 * prompt with a tool call that runs for three seconds. During those seconds a second prompt is sent: the chat holds it and the agent sees it
 * only after the first turn has ended, in order; the user interface thread answers at once all the while; and the window that has the focus
 * is the window that had it before.
 */
public class ChatBusyTest {

    private static final long LONG_TOOL_CALL_MILLIS = 3000;

    /** A stand-in ACP agent: it logs each prompt with the time it arrived, and answers it with a tool call that takes three seconds. */
    private static final String AGENT = "const fs = require('fs'); const log = process.argv[1]; const started = Date.now(); let running = null;"
        + "const send = (o) => process.stdout.write(JSON.stringify(o) + '\\n');"
        + "require('readline').createInterface({ input: process.stdin }).on('line', (line) => {"
        + "  const m = JSON.parse(line);"
        + "  if (m.method === 'initialize') { send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: 1, agentCapabilities: {}, authMethods: [] } }); }"
        + "  else if (m.method === 'session/new') { send({ jsonrpc: '2.0', id: m.id, result: { sessionId: 's1' } }); }"
        + "  else if (m.method === 'session/cancel') {"
        + "    fs.appendFileSync(log, (Date.now() - started) + ' cancel\\n');"
        + "    if (running) { clearTimeout(running.timer); send({ jsonrpc: '2.0', id: running.id, result: { stopReason: 'cancelled' } }); running = null; }"
        + "  }"
        + "  else if (m.method === 'session/prompt') {"
        + "    fs.appendFileSync(log, (Date.now() - started) + ' ' + m.params.prompt[0].text + '\\n');"
        + "    const id = 't' + m.id;"
        + "    send({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: 's1', update: { sessionUpdate: 'tool_call', toolCallId: id,"
        + "      title: 'jawata: a long tool call', kind: 'other', status: 'in_progress' } } });"
        + "    running = { id: m.id, timer: setTimeout(() => {"
        + "      send({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: 's1', update: { sessionUpdate: 'tool_call_update', toolCallId: id, status: 'completed' } } });"
        + "      send({ jsonrpc: '2.0', id: m.id, result: { stopReason: 'end_turn' } });"
        + "      running = null;"
        + "    }, " + LONG_TOOL_CALL_MILLIS + ") };"
        + "  }"
        + "});";

    /** The prompts the agent has logged so far, in order; none before it has logged one. */
    private static List<String> readableLines(Path log) {
        try {
            return Files.readAllLines(log);
        } catch (IOException notYet) {
            return List.of();
        }
    }

    @Test
    public void aCancelDuringALongToolCallReachesTheAgentAtOnceAndIsNotHeldBehindTheTurn() throws Exception {
        Path log = Files.createTempFile("jawata-cancel-", ".log");
        try {
            FixedChatSettings settings = new FixedChatSettings(List.of("node", "-e", AGENT, log.toString()), Map.of(), FixedChatSettings.withoutAgent().workingFolder());
            try (HostTestPage page = new HostTestPage(settings)) {
                page.run("window.playByCommands({turns: 1});");
                page.command("connectAgent", Map.of());
                page.command("sendPrompt", Map.of("text", "first"));
                Await.found("the first prompt at the agent", () -> readableLines(log).stream().filter(line -> line.endsWith(" first")).findFirst());
                Thread.sleep(500);
                page.command("cancelTurn", Map.of());
                String cancel = Await.found("the cancel at the agent", () -> readableLines(log).stream().filter(line -> line.endsWith(" cancel")).findFirst());
                long gap = Long.parseLong(cancel.split(" ")[0]) - Long.parseLong(readableLines(log).get(0).split(" ")[0]);
                assertTrue(gap < LONG_TOOL_CALL_MILLIS - 500, "the cancel reached the agent while the tool call was still running, not after it: " + gap + " ms after the prompt");
                page.waitForCall("played");
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }

    @Test
    public void aSecondPromptWaitsBehindALongToolCallWhileTheInterfaceAndTheFocusStayAsTheyWere() throws Exception {
        Path log = Files.createTempFile("jawata-busy-", ".log");
        try {
            FixedChatSettings settings = new FixedChatSettings(List.of("node", "-e", AGENT, log.toString()), Map.of(), FixedChatSettings.withoutAgent().workingFolder());
            try (HostTestPage page = new HostTestPage(settings)) {
                Display display = Display.getDefault();
                Shell focusBefore = UiThread.call(display::getActiveShell);
                page.run("window.playByCommands({turns: 2});");
                page.command("connectAgent", Map.of());
                page.command("sendPrompt", Map.of("text", "first"));
                Await.found("the first prompt at the agent", () -> readableLines(log).stream().filter(line -> line.endsWith(" first")).findFirst());
                page.command("sendPrompt", Map.of("text", "second"));

                long worstMillis = 0;
                long until = System.nanoTime() + 2_000_000_000L;
                while (System.nanoTime() < until) {
                    long asked = System.nanoTime();
                    UiThread.call(() -> null);
                    worstMillis = Math.max(worstMillis, (System.nanoTime() - asked) / 1_000_000);
                    Thread.sleep(50);
                }
                assertTrue(worstMillis < 500, "the user interface thread answered within half a second throughout the tool call: worst " + worstMillis + " ms");
                assertEquals(1, readableLines(log).size(), "the second prompt is held while the first turn runs: " + readableLines(log));
                assertSame(focusBefore, UiThread.call(display::getActiveShell), "the window that has the focus is the one that had it");

                page.waitForCall("played");
                List<String> arrived = readableLines(log);
                assertEquals(2, arrived.size(), "then the second prompt is sent: " + arrived);
                assertTrue(arrived.get(0).endsWith(" first") && arrived.get(1).endsWith(" second"), "in the order the person sent them: " + arrived);
                long gap = Long.parseLong(arrived.get(1).split(" ")[0]) - Long.parseLong(arrived.get(0).split(" ")[0]);
                assertTrue(gap >= LONG_TOOL_CALL_MILLIS - 500, "after the long tool call had ended, not before: " + gap + " ms between them");
                assertSame(focusBefore, UiThread.call(display::getActiveShell), "and the focus is still where it was");
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
