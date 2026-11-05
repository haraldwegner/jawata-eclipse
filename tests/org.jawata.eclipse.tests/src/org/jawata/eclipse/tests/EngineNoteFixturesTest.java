package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.jawata.eclipse.host.EclipseWorkspace;
import org.jawata.eclipse.host.EngineNoteWriter;
import org.jawata.mcp.models.EngineNotes;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan 25a.1 (A16-9), the note-digest test: this plug-in keeps a checked-in copy of the engine's canonical note fixture set (Stage 8, contract
 * §14.1, schema 1: a live note, a stale pid, a reused pid, a malformed note, a newer major), the same files studio keeps. The copy is compared
 * byte for byte, by digest, with the set the engine's release carries (the release's test-support jar, fetched by scripts/fetch-engine.sh); the
 * engine's own reader classifies the copy the way studio's reader classifies its copy; and the note this plug-in writes has the fields the live
 * fixture has, so a change to the writer or to the set cannot drift apart unnoticed.
 */
public class EngineNoteFixturesTest {

    private static final String ENGINE_SET = "test-resources/engine-notes/";
    private static final String OUR_SET = "resources/engine-notes/";
    private static final String SUMS = "SHA256SUMS";
    private static final String LIVE = "eclipse-1111111111111111.json";
    private static final String STALE = "eclipse-2222222222222222.json";
    private static final String REUSED = "eclipse-3333333333333333.json";
    private static final String MALFORMED = "eclipse-4444444444444444.json";
    private static final String NEWER = "eclipse-5555555555555555.json";
    private static final Instant NOTED_START = Instant.parse("2026-10-22T09:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The fake operating system the fixtures name: pid 4242 is no real process, and only the one that started at the noted time is alive. */
    private static final EngineNotes.Liveness FAKE_OS = (pid, started) -> pid == 4242 && NOTED_START.equals(started);

    private static byte[] engineBytes(String name) throws Exception {
        try (InputStream in = EngineNoteFixturesTest.class.getClassLoader().getResourceAsStream(ENGINE_SET + name)) {
            assertNotNull(in, "the engine release's fixture " + name + " (the test-support jar is fetched by scripts/fetch-engine.sh)");
            return in.readAllBytes();
        }
    }

    private static byte[] ourBytes(String name) throws Exception {
        java.net.URL entry = FrameworkUtil.getBundle(EngineNoteFixturesTest.class).getEntry(OUR_SET + name);
        assertNotNull(entry, "this plug-in's checked-in copy of " + name + " (tests/org.jawata.eclipse.tests/resources/engine-notes)");
        try (InputStream in = entry.openStream()) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** The file names a SHA256SUMS text lists, with their digests, in file-name order. */
    private static Map<String, String> listed(String sums) {
        Map<String, String> listed = new TreeMap<>();
        for (String line : sums.split("\\R")) {
            if (!line.isBlank()) {
                String[] parts = line.trim().split("\\s+", 2);
                listed.put(parts[1].trim(), parts[0]);
            }
        }
        return listed;
    }

    @Test
    public void theCopyIsByteIdenticalToTheEnginesFixtureSetByDigest() throws Exception {
        String engineSums = new String(engineBytes(SUMS), StandardCharsets.UTF_8);
        Map<String, String> wanted = listed(engineSums);
        assertEquals(5, wanted.size(), "the engine's set is five notes: " + wanted.keySet());

        assertEquals(wanted, listed(new String(ourBytes(SUMS), StandardCharsets.UTF_8)), "this plug-in's SHA256SUMS is the engine's");
        for (Map.Entry<String, String> note : wanted.entrySet()) {
            byte[] ours = ourBytes(note.getKey());
            assertEquals(note.getValue(), sha256(ours), note.getKey() + " differs from the engine's fixture");
            assertTrue(java.util.Arrays.equals(engineBytes(note.getKey()), ours), note.getKey() + " is not the engine's bytes");
        }
    }

    @Test
    public void theEnginesReaderClassifiesTheCopyAsStudioClassifiesItsCopy() throws Exception {
        Path dir = Files.createTempDirectory("jawata-note-fixtures-");
        try {
            for (String name : List.of(LIVE, STALE, REUSED, MALFORMED, NEWER)) {
                Files.write(dir.resolve(name), ourBytes(name));
            }

            Map<String, EngineNotes.Reading> readings = EngineNotes.read(dir, FAKE_OS).stream()
                .collect(Collectors.toMap(EngineNotes.Reading::fileName, r -> r));

            assertEquals(new TreeSet<>(Set.of(LIVE, STALE, REUSED, MALFORMED, NEWER)), new TreeSet<>(readings.keySet()));
            assertEquals(EngineNotes.Status.LIVE, readings.get(LIVE).status(), readings.get(LIVE).detail());
            assertEquals(43123, readings.get(LIVE).sibling().orElseThrow().port(), "the live note carries the port of its endpoint");
            assertEquals(EngineNotes.Status.STALE, readings.get(STALE).status(), "a note whose process is gone is stale");
            assertEquals(EngineNotes.Status.STALE, readings.get(REUSED).status(), "a pid reused after a crash, started at another time, is stale");
            assertEquals(EngineNotes.Status.MALFORMED, readings.get(MALFORMED).status(), "a damaged note is reported and does not hide the others");
            assertEquals(EngineNotes.Status.NEWER_MAJOR, readings.get(NEWER).status(), "a note of a higher major is named, not read further");
        } finally {
            try (Stream<Path> left = Files.list(dir)) {
                left.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void theNoteThisPluginWritesHasTheFieldsOfTheLiveFixture() throws Exception {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = WorkspaceProjects.open("jawata-notefields-");
        Path dir = Files.createTempDirectory("jawata-note-fields-");
        try {
            EngineNoteWriter writer = new EngineNoteWriter(workspace, new EclipseWorkspace(workspace.getRoot()), dir, "4.4.20",
                ILog.of(FrameworkUtil.getBundle(EngineNoteFixturesTest.class)));
            writer.ready(new McpEndpoint(URI.create("http://127.0.0.1:1/mcp"), Optional.empty()));

            JsonNode written = JSON.readTree(Files.readString(writer.file()));
            JsonNode fixture = JSON.readTree(ourBytes(LIVE));
            assertEquals(fieldNames(fixture), fieldNames(written), "the plug-in's note has the top-level fields of the engine's live fixture");
            assertTrue(written.path("projects").size() > 0, "the workspace has the project this test made");
            assertEquals(fieldNames(fixture.path("projects").get(0)), fieldNames(written.path("projects").get(0)),
                "and a project entry has the fixture's fields");
            assertEquals(fixture.path("schema").asText(), written.path("schema").asText(), "in the schema the fixture is in");
            writer.close();
        } finally {
            WorkspaceProjects.deleteQuietly(project);
            try (Stream<Path> left = Files.list(dir)) {
                left.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
