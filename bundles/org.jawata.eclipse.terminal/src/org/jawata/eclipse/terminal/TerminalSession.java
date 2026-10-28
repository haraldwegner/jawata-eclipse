package org.jawata.eclipse.terminal;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * What the terminal's page talks to: a shell it can type into, whose output it can show and whose size it can set. The seam of the
 * jawata terminal (ARCHITECTURE-terminal.md): the page and the view never see how the shell runs, so a test can put a fake behind them.
 */
public interface TerminalSession extends AutoCloseable {

    /**
     * Sends what the person typed to the shell.
     *
     * @param input the bytes the shell reads
     * @throws IOException when the shell is gone
     */
    void write(byte[] input) throws IOException;

    /**
     * Says who gets what the shell writes; the listener is called on the thread that reads the shell, so it must not block.
     *
     * @param listener receives each chunk of the shell's output, as bytes
     */
    void onOutput(Consumer<byte[]> listener);

    /**
     * Tells the shell the size of the window it draws in, so a full-screen program lays itself out to fit.
     *
     * @param columns characters per line
     * @param rows lines
     */
    void resize(int columns, int rows);

    /** Ends the shell and what reads it. */
    @Override
    void close();
}
