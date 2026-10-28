package org.jawata.eclipse.chat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Java half of the chat's agent host (plan Stage 21, step 21.3). The chat's core runs in the page and cannot start a program, so
 * this class starts the ACP agent for it and relays whole messages, nothing else: one line the agent writes to its standard output is
 * one message to the page, and one message the page sends is one line on the agent's standard input. It reads no message, adds no
 * field and keeps no state of the conversation; what the messages mean is the core's. The agent's standard error goes to the log, and
 * the end of the program is reported once, after every message it wrote has been handed on.
 */
public final class AgentRelay implements AutoCloseable {

    /** Who is told what the agents do. Called on the relay's own threads, never on the user interface thread. */
    public interface Listener {
        /** One message the agent wrote, as the JSON text of one line. */
        void onMessage(String agentId, String message);

        /**
         * The agent's program has ended.
         *
         * @param code the exit code, or null when the program was ended by {@link AgentRelay#kill}, as the core expects of a signal
         */
        void onClosed(String agentId, Integer code);

        /** Something about the agent went wrong that the core should show: a message could not be written. */
        void onError(String agentId, String message);
    }

    private final Listener listener;
    private final Consumer<String> log;
    private final Map<String, Running> agents = new ConcurrentHashMap<>();

    /**
     * @param listener told every message, the end and every failure of every agent
     * @param log receives the agent's standard error, one line at a time, and the relay's own notes
     */
    public AgentRelay(Listener listener, Consumer<String> log) {
        this.listener = listener;
        this.log = log;
    }

    /**
     * Starts the agent's program.
     *
     * @param agentId the id the core gave this agent, which it uses in every later call: the core needs its handle before the program
     *            has started, so it names the agent and the relay keeps that name
     * @param command the program and its arguments, as the person configured them; a bare program name is looked for on the path
     * @param environment variables added to the environment this program runs in
     * @param workingFolder where the agent works, or null for the folder Eclipse runs in
     * @return the id the agent was started under, the same as the one given
     * @throws IOException if the program cannot be started
     */
    public String start(String agentId, List<String> command, Map<String, String> environment, Path workingFolder) throws IOException {
        if (command.isEmpty()) {
            throw new IOException("No agent command is set.");
        }
        ProcessBuilder builder = new ProcessBuilder(executable(command, pathEntries(System.getenv("PATH")), isWindows()));
        builder.environment().putAll(environment);
        if (workingFolder != null) {
            builder.directory(workingFolder.toFile());
        }
        if (agents.containsKey(agentId)) {
            throw new IOException("An agent called " + agentId + " is already running.");
        }
        Process process = builder.start();
        Running running = new Running(agentId, process);
        agents.put(agentId, running);
        running.begin();
        return agentId;
    }

    /**
     * Writes one message to the agent.
     *
     * @param agentId the agent {@link #start} returned
     * @param message the JSON text of one message, which must not contain a line break
     * @return false when the agent is not running or the message could not be written, so the core can tell the person
     */
    public boolean send(String agentId, String message) {
        Running running = agents.get(agentId);
        if (running == null) {
            listener.onError(agentId, "The agent is not running.");
            return false;
        }
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            listener.onError(agentId, "A message with a line break cannot be sent to the agent: one line is one message.");
            return false;
        }
        return running.write(message);
    }

    /**
     * Ends the agent's program.
     *
     * @param agentId the agent {@link #start} returned
     * @return false when there is no such agent running
     */
    public boolean kill(String agentId) {
        Running running = agents.get(agentId);
        if (running == null) {
            return false;
        }
        running.killed = true;
        running.process.destroy();
        return true;
    }

    /** Ends every agent still running. */
    @Override
    public void close() {
        for (String id : List.copyOf(agents.keySet())) {
            kill(id);
        }
    }

    /**
     * The command line the system can start. On Windows a program the path holds as {@code name.cmd} or {@code name.bat} cannot be
     * started by its bare name, and {@code npx} is one: it is found there and run through the command interpreter.
     *
     * @param command the configured program and arguments
     * @param pathEntries the folders of the path, in order
     * @param windows whether the system is Windows
     * @return the command to hand to the system
     */
    public static List<String> executable(List<String> command, List<Path> pathEntries, boolean windows) {
        String program = command.get(0);
        if (!windows || program.contains("/") || program.contains("\\") || program.contains(".")) {
            return command;
        }
        for (Path folder : pathEntries) {
            for (String extension : new String[] {".exe", ".cmd", ".bat"}) {
                Path candidate = folder.resolve(program + extension);
                if (Files.isRegularFile(candidate)) {
                    List<String> found = new ArrayList<>(command);
                    found.set(0, candidate.toString());
                    return extension.equals(".exe") ? found : throughInterpreter(found);
                }
            }
        }
        return command;
    }

    private static List<String> throughInterpreter(List<String> command) {
        List<String> line = new ArrayList<>(List.of("cmd", "/c"));
        line.addAll(command);
        return line;
    }

    private static List<Path> pathEntries(String path) {
        List<Path> folders = new ArrayList<>();
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    folders.add(Path.of(entry));
                }
            }
        }
        return folders;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    /** One program the relay has started, with the threads that read from it. */
    private final class Running {
        private final String id;
        private final Process process;
        private final OutputStream toAgent;
        private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<>();
        private volatile boolean killed;

        Running(String id, Process process) {
            this.id = id;
            this.process = process;
            this.toAgent = process.getOutputStream();
        }

        void begin() {
            Thread messages = pump("messages", process.getInputStream(), listener::onMessage);
            Thread notes = pump("stderr", process.getErrorStream(), (agent, line) -> log.accept("[agent " + agent + "] " + line));
            Thread writing = writer();
            writing.start();
            Thread ending = new Thread(() -> {
                Integer code = null;
                try {
                    code = process.waitFor();
                    writing.interrupt();
                    messages.join();
                    notes.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                agents.remove(id);
                listener.onClosed(id, killed ? null : code);
            }, "jawata chat agent " + id + " end");
            ending.setDaemon(true);
            ending.start();
        }

        private Thread pump(String what, InputStream stream, BiConsumer<String, String> lines) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isEmpty()) {
                            lines.accept(id, line);
                        }
                    }
                } catch (IOException closed) {
                    log.accept("The agent's " + what + " stopped: " + closed.getMessage());
                }
            }, "jawata chat agent " + id + " " + what);
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        /** Queues one message for the writer thread: the caller is the user interface thread, which a full pipe must not block. */
        boolean write(String message) {
            return outgoing.offer(message);
        }

        private Thread writer() {
            Thread thread = new Thread(() -> {
                try {
                    while (true) {
                        byte[] line = (outgoing.take() + "\n").getBytes(StandardCharsets.UTF_8);
                        toAgent.write(line);
                        toAgent.flush();
                    }
                } catch (InterruptedException ended) {
                    Thread.currentThread().interrupt();
                } catch (IOException failed) {
                    listener.onError(id, "The agent could not be written to: " + failed.getMessage());
                }
            }, "jawata chat agent " + id + " writer");
            thread.setDaemon(true);
            return thread;
        }
    }
}
