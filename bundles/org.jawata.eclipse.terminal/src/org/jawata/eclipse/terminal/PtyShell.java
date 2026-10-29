package org.jawata.eclipse.terminal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.cdt.utils.pty.PTY;
import org.eclipse.cdt.utils.spawner.ProcessFactory;

/**
 * A shell on a pseudo-terminal, through the pty support Eclipse's own Terminal already uses (the C/C++ tooling's native bundle, public API,
 * with natives for Linux, macOS and Windows). The program thinks it runs in a real terminal, so full-screen programs and colours work, and
 * the window size can be told to it. This is the production side of {@link TerminalSession}.
 */
public final class PtyShell implements TerminalSession {

    private final PTY pty;
    private final Process process;
    private final CopyOnWriteArrayList<Consumer<byte[]>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean pumping = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    /**
     * Starts a program on a new pseudo-terminal.
     *
     * @param command the program and its arguments
     * @param folder the folder it starts in; null for the person's home
     * @throws IOException when no pseudo-terminal can be made or the program cannot start
     */
    public PtyShell(String[] command, File folder) throws IOException {
        if (!PTY.isSupported(PTY.Mode.TERMINAL)) {
            throw new IOException("this system gives no pseudo-terminal to the C/C++ tooling's pty support");
        }
        pty = new PTY(PTY.Mode.TERMINAL);
        process = ProcessFactory.getFactory().exec(command, environment(), folder, pty);
    }

    /**
     * The shell the person's environment names ({@code $SHELL}), else the Windows command shell, else bash.
     *
     * @return a command for {@link #PtyShell(String[], File)}
     */
    public static String[] defaultShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            return new String[] { shell };
        }
        String windows = System.getenv("COMSPEC");
        return windows != null ? new String[] { windows } : new String[] { "/bin/bash" };
    }

    private static String[] environment() {
        Map<String, String> env = new TreeMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        return env.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
    }

    private void pump() {
        byte[] buffer = new byte[8192];
        try (InputStream in = pty.getInputStream()) {
            for (int read = in.read(buffer); read >= 0 && !closed; read = in.read(buffer)) {
                byte[] chunk = Arrays.copyOf(buffer, read);
                listeners.forEach(listener -> listener.accept(chunk));
            }
        } catch (IOException ended) {
            // the shell is gone; nothing more to read
        }
    }

    @Override
    public void write(byte[] input) throws IOException {
        OutputStream out = pty.getOutputStream();
        out.write(input);
        out.flush();
    }

    /** The shell's output is read from the first listener on, so nothing it prints before anyone listens is dropped. */
    @Override
    public void onOutput(Consumer<byte[]> listener) {
        listeners.add(listener);
        if (pumping.compareAndSet(false, true)) {
            Thread reader = new Thread(this::pump, "jawata terminal output");
            reader.setDaemon(true);
            reader.start();
        }
    }

    @Override
    public void resize(int columns, int rows) {
        pty.setTerminalSize(columns, rows);
    }

    @Override
    public void close() {
        closed = true;
        process.destroy();
    }
}
