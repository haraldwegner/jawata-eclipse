package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EclipseWorkspace;
import org.jawata.eclipse.host.EngineNoteWriter;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.mcp.models.EngineNotes;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Plan Stage 16, step 16.5: the note an Eclipse engine leaves for studio and for sibling engines (contract §14.1). The running
 * engine's own note is read back by the engine's own reader, which is the other side of the contract, and is named by the hash the
 * contract fixes; a project added to the workspace rewrites it; a clean stop deletes it; and a note whose process has really ended
 * is classified stale by the engine's own reader, which is what a killed Eclipse leaves behind.
 */
public class EngineNoteTest {

    private static final IWorkspace WORKSPACE = ResourcesPlugin.getWorkspace();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Optional<Path> noteIn(Path dir) {
        try (Stream<Path> listing = Files.isDirectory(dir) ? Files.list(dir) : Stream.<Path>empty()) {
            return listing.filter(p -> p.getFileName().toString().matches("eclipse-[0-9a-f]{16}\\.json")).findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<String> projectsIn(Path note) {
        try {
            JsonNode projects = JSON.readTree(Files.readString(note)).path("projects");
            return projects.findValuesAsText("name");
        } catch (Exception e) {
            return List.of();
        }
    }

    @Test
    public void theRunningEnginesNoteIsReadBackLiveByTheEnginesOwnReader() throws Exception {
        Path dir = Path.of(System.getProperty("jawata.engine.notes.dir"));
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        Path note = Await.found("the note was written", () -> noteIn(dir));

        JsonNode written = JSON.readTree(Files.readString(note));
        assertEquals("jawata.engine-note/1", written.path("schema").asText());
        assertEquals("eclipse", written.path("hostKind").asText());
        assertEquals(ProcessHandle.current().pid(), written.path("pid").asLong(), "the note names this process");
        assertEquals(at.url().toString(), written.path("endpoint").asText(), "and the address the engine listens at");
        assertTrue(written.path("workspacePath").asText().length() > 0);
        Path canonical = Path.of(WORKSPACE.getRoot().getLocation().toOSString()).toRealPath();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals("eclipse-" + hash.substring(0, 16) + ".json", note.getFileName().toString(),
            "the file is named by the first 16 hex of the SHA-256 of the canonical workspace path (contract §14.1)");
        assertFalse(written.has("key"), "the note carries no key: the endpoint inside Eclipse has none");

        List<EngineNotes.Reading> readings = EngineNotes.read(dir, EngineNotes.OS);
        assertEquals(1, readings.size(), "one Eclipse workspace, one note: " + readings);
        assertEquals(EngineNotes.Status.LIVE, readings.get(0).status(), readings.get(0).detail());
        assertEquals(at.url().getPort(), readings.get(0).sibling().orElseThrow().port(), "the reader finds the engine at the same port");
    }

    @Test
    public void aProjectAddedToTheWorkspaceIsInTheNextNote() throws Exception {
        Path dir = Path.of(System.getProperty("jawata.engine.notes.dir"));
        Path note = Await.found("the note was written", () -> noteIn(dir));
        IProject project = WorkspaceProjects.open("jawata-noted-");
        try {
            assertTrue(Await.until(() -> projectsIn(note).contains(project.getName())),
                "the note was rewritten with the new project: " + projectsIn(note));
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
        assertTrue(Await.until(() -> !projectsIn(note).contains(project.getName())), "and again without it");
    }

    @Test
    public void aCleanStopDeletesTheNoteAndAGoneProcessLeavesAStaleOne() throws Exception {
        Path dir = Files.createTempDirectory("jawata-notes-");
        try {
            EngineNoteWriter writer = new EngineNoteWriter(WORKSPACE, new EclipseWorkspace(WORKSPACE.getRoot()), dir, "4.4.17",
                ILog.of(FrameworkUtil.getBundle(EngineNoteTest.class)));
            writer.ready(new McpEndpoint(URI.create("http://127.0.0.1:1/mcp"), Optional.empty()));
            assertTrue(Files.exists(writer.file()), "the note is written when the engine is ready");
            assertEquals(EngineNotes.Status.LIVE, EngineNotes.read(dir, EngineNotes.OS).get(0).status(),
                "the engine's own reader finds this process, which the note names, alive");

            // A killed Eclipse leaves its note with a process id nobody has: a real process that has ended stands for it.
            Process ended = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            ended.waitFor();
            ObjectNode killed = (ObjectNode) JSON.readTree(Files.readString(writer.file()));
            killed.put("pid", ended.pid());
            Files.writeString(writer.file(), JSON.writeValueAsString(killed), StandardCharsets.UTF_8);
            assertEquals(EngineNotes.Status.STALE, EngineNotes.read(dir, EngineNotes.OS).get(0).status(),
                "a note whose process has ended is stale, which is all a killed Eclipse leaves");

            writer.close();

            assertFalse(Files.exists(writer.file()), "a clean stop deletes the note");
        } finally {
            try (Stream<Path> left = Files.list(dir)) {
                left.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }
}
