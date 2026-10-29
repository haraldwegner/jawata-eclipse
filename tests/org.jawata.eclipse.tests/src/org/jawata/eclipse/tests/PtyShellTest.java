package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.jawata.eclipse.terminal.PtyShell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 13c, step 3: the production side of the terminal's seam. A real shell on a real pseudo-terminal answers what is typed to it,
 * takes the window size, and passes UTF-8 through, which is what a full-screen program such as Claude Code needs.
 */
public class PtyShellTest {

    private PtyShell shell;
    private final ByteArrayOutputStream seen = new ByteArrayOutputStream();

    @BeforeEach
    public void startAShell() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "the POSIX shell commands below are for Linux and macOS");
        shell = new PtyShell(new String[] { "/bin/sh" }, null);
        shell.onOutput(chunk -> {
            synchronized (seen) {
                seen.write(chunk, 0, chunk.length);
            }
        });
    }

    @AfterEach
    public void endTheShell() {
        if (shell != null) {
            shell.close();
        }
    }

    private String output() {
        synchronized (seen) {
            return seen.toString(StandardCharsets.UTF_8);
        }
    }

    private void type(String text) throws Exception {
        shell.write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aTypedCommandIsRunAndAnswered() throws Exception {
        type("echo answer-$((6*7))\n");
        assertTrue(Await.until(() -> output().contains("answer-42")), "the shell answered the typed command: " + output());
    }

    @Test
    public void theWindowSizeReachesTheProgram() throws Exception {
        shell.resize(100, 30);
        type("stty size\n");
        assertTrue(Await.until(() -> output().contains("30 100")), "the program sees 30 rows and 100 columns: " + output());
    }

    /** The page registers its listener a moment after the shell starts; what the shell printed in between must still arrive. */
    @Test
    public void outputPrintedBeforeAnyoneListensIsKept() throws Exception {
        PtyShell early = new PtyShell(new String[] { "/bin/sh", "-c", "echo printed-early; sleep 5" }, null);
        try {
            Thread.sleep(500);
            StringBuilder got = new StringBuilder();
            early.onOutput(chunk -> {
                synchronized (got) {
                    got.append(new String(chunk, StandardCharsets.UTF_8));
                }
            });
            assertTrue(Await.until(() -> {
                synchronized (got) {
                    return got.toString().contains("printed-early");
                }
            }), "the line printed before the first listener arrived: " + got);
        } finally {
            early.close();
        }
    }

    @Test
    public void utf8GoesThroughBothWays() throws Exception {
        type("echo ö→│▄\n");
        assertTrue(Await.until(() -> output().contains("ö→│▄")), "box and block characters come back as typed: " + output());
    }
}
