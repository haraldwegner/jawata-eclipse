package org.jawata.eclipse.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.McpEndpoint;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The note an engine running inside Eclipse leaves for the machine (plan Stage 16, step 16.5; contract §14.1, producer): one small
 * file per Eclipse workspace, written when the listener is up so studio can list the workspace as running and a sibling engine can
 * reach this one, rewritten when the workspace's projects change, and deleted at a clean stop. A killed Eclipse leaves its note
 * behind, which is how a reader knows it was killed: the process the note names is gone. The engine's own {@code EngineNotes} is the
 * reader of this file and decides what each note is worth; this class only writes, in the schema that reader understands.
 */
public final class EngineNoteWriter implements IResourceChangeListener, AutoCloseable {

    /** The schema field of every note this writes: the major version a reader checks first. */
    static final String SCHEMA = "jawata.engine-note/1";

    private static final String CONTRACT_VERSION = "1.0";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IWorkspace workspace;
    private final HostWorkspace folders;
    private final Path file;
    private final Path workspacePath;
    private final String engineVersion;
    private final ILog log;
    private final Instant startedAt = ProcessHandle.current().info().startInstant().orElseGet(Instant::now).truncatedTo(ChronoUnit.SECONDS);

    private McpEndpoint endpoint;
    private boolean listening;

    /**
     * @param workspace     the workspace Eclipse has open, whose projects the note lists and whose changes rewrite it
     * @param folders       how the projects are read, so the note says what the engine says
     * @param notesDir      the folder every Eclipse engine on the machine leaves its note in
     * @param engineVersion the version of the engine running here
     * @param log           where a note that cannot be written is reported
     */
    public EngineNoteWriter(IWorkspace workspace, HostWorkspace folders, Path notesDir, String engineVersion, ILog log) {
        this.workspace = workspace;
        this.folders = folders;
        this.engineVersion = engineVersion;
        this.log = log;
        this.workspacePath = canonical(Path.of(workspace.getRoot().getLocation().toOSString()));
        this.file = notesDir.resolve("eclipse-" + shortHash(workspacePath.toString()) + ".json");
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException unresolvable) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }

    /** The file this workspace's note is, or will be, written to. */
    public Path file() {
        return file;
    }

    /**
     * The engine is listening: writes the note, and from here rewrites it whenever the workspace gains, loses, opens or closes a
     * project.
     *
     * @param at where the engine can be reached
     */
    public synchronized void ready(McpEndpoint at) {
        endpoint = at;
        write();
        if (!listening) {
            workspace.addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
            listening = true;
        }
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null) {
            return;
        }
        for (IResourceDelta project : delta.getAffectedChildren()) {
            boolean projectSetChanged = project.getKind() == IResourceDelta.ADDED || project.getKind() == IResourceDelta.REMOVED
                || (project.getFlags() & IResourceDelta.OPEN) != 0;
            if (projectSetChanged) {
                synchronized (this) {
                    write();
                }
                return;
            }
        }
    }

    private void write() {
        if (endpoint == null) {
            return;
        }
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("schema", SCHEMA);
        note.put("hostKind", "eclipse");
        note.put("workspacePath", workspacePath.toString());
        note.put("workspaceName", workspacePath.getFileName() == null ? workspacePath.toString() : workspacePath.getFileName().toString());
        note.put("pid", ProcessHandle.current().pid());
        note.put("processStartedAt", startedAt.toString());
        note.put("engineVersion", engineVersion);
        note.put("contractVersion", CONTRACT_VERSION);
        note.put("updatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        note.put("endpoint", endpoint.url().toString());
        List<Map<String, Object>> projects = new ArrayList<>();
        for (HostWorkspace.ProjectFolder folder : folders.folders()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", folder.name());
            entry.put("path", folder.path().toString());
            entry.put("open", folder.open());
            projects.add(entry);
        }
        note.put("projects", projects);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
            Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(note), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.log(new Status(IStatus.WARNING, EclipseEngine.PLUGIN_ID, "The jawata engine note could not be written to " + file + ": " + e.getMessage(), e));
        }
    }

    /** A clean stop: Eclipse stops telling this about projects, and the note is deleted, because the engine it announced is gone. */
    @Override
    public synchronized void close() {
        if (listening) {
            workspace.removeResourceChangeListener(this);
            listening = false;
        }
        endpoint = null;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.log(new Status(IStatus.WARNING, EclipseEngine.PLUGIN_ID, "The jawata engine note " + file + " could not be deleted: " + e.getMessage(), e));
        }
    }
}
