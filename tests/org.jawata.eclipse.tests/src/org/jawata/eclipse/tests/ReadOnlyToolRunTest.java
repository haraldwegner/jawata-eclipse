package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 16, step 16.6, the exit criterion "the snapshot is unchanged": a full read-only tool run over projects of the three
 * shapes the plan names (Maven, Gradle, a plug-in) leaves every {@code .project}, {@code .classpath}, {@code .settings/**} and the
 * workspace's own preference files byte for byte as they were (spec D2: the engine reads what Eclipse already knows and does not
 * write to it). The three are the projects' on-disk shapes: the build-tool and PDE natures are put on a project only when this
 * Eclipse has them, so a run in a download that carries m2e, Buildship and PDE exercises the real ones and the run in Tycho's own
 * test runtime exercises the same files without the natures. {@link #naturesHere} says which it was.
 */
public class ReadOnlyToolRunTest {

    private static final String M2E = "org.eclipse.m2e.core.maven2Nature";
    private static final String BUILDSHIP = "org.eclipse.buildship.core.gradleprojectnature";
    private static final String PDE = "org.eclipse.pde.PluginNature";

    private static void write(IProject project, String path, String content) throws CoreException {
        IFile file = project.getFile(path);
        if (file.getParent() instanceof IFolder folder) {
            mkdirs(folder);
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        if (file.exists()) {
            file.setContents(stream, true, false, null);
        } else {
            file.create(stream, true, null);
        }
    }

    private static void mkdirs(IFolder folder) throws CoreException {
        if (folder.exists()) {
            return;
        }
        if (folder.getParent() instanceof IFolder parent) {
            mkdirs(parent);
        }
        folder.create(true, true, null);
    }

    /** The natures of {@code wanted} that this Eclipse has; the rest are left off, because Eclipse refuses a nature it does not know. */
    private static List<String> naturesOf(String... wanted) {
        List<String> present = new ArrayList<>();
        for (String id : wanted) {
            if (ResourcesPlugin.getWorkspace().getNatureDescriptor(id) != null) {
                present.add(id);
            }
        }
        return present;
    }

    private static void addNatures(IProject project, List<String> ids) throws CoreException {
        IProjectDescription description = project.getDescription();
        List<String> all = new ArrayList<>(List.of(description.getNatureIds()));
        all.addAll(ids);
        description.setNatureIds(all.toArray(String[]::new));
        project.setDescription(description, null);
    }

    private static IProject maven() throws CoreException {
        IProject project = WorkspaceProjects.java("jawata-fixture-maven-");
        write(project, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
            + "<version>1</version></project>\n");
        write(project, ".settings/org.eclipse.m2e.core.prefs", "activeProfiles=\neclipse.preferences.version=1\nversion=1\n");
        addNatures(project, naturesOf(M2E));
        return project;
    }

    private static IProject gradle() throws CoreException {
        IProject project = WorkspaceProjects.java("jawata-fixture-gradle-");
        write(project, "build.gradle", "plugins { id 'java' }\n");
        write(project, ".settings/org.eclipse.buildship.core.prefs", "connection.project.dir=\neclipse.preferences.version=1\n");
        addNatures(project, naturesOf(BUILDSHIP));
        return project;
    }

    private static IProject plugin() throws CoreException {
        IProject project = WorkspaceProjects.java("jawata-fixture-plugin-");
        write(project, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: fixture.plugin\n"
            + "Bundle-Version: 1.0.0\n");
        write(project, "build.properties", "source.. = src/\nbin.includes = META-INF/,.\n");
        addNatures(project, naturesOf(PDE));
        return project;
    }

    /** Which of the three natures this Eclipse has, for the failure message of any assertion. */
    private static String naturesHere() {
        return "natures present here: m2e=" + !naturesOf(M2E).isEmpty() + ", buildship=" + !naturesOf(BUILDSHIP).isEmpty() + ", pde="
            + !naturesOf(PDE).isEmpty();
    }

    /** Every file the engine must not touch, with its content hash: the settings of each project, and the workspace's own. */
    private static Map<String, String> snapshot(List<IProject> projects) throws Exception {
        Map<String, String> files = new TreeMap<>();
        for (IProject project : projects) {
            Path root = project.getLocation().toFile().toPath();
            // the whole project folder: a file the engine left anywhere in it is seen; bin/, target/ and build/ are the output of
            // Eclipse's own build (the real m2e writes target/classes on every build), not settings
            hashAll(files, root, root);
        }
        Path workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        hashAll(files, workspace, workspace.resolve(".metadata/.plugins/org.eclipse.core.runtime/.settings"));
        return files;
    }

    private static void hashAll(Map<String, String> into, Path base, Path dir) throws Exception {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(Files::isRegularFile).filter(f -> !List.of("bin", "target", "build").contains(base.relativize(f).getName(0).toString())).toList()) {
                    hash(into, base, file);
                }
            }
        }
    }

    private static void hash(Map<String, String> into, Path base, Path file) throws Exception {
        if (Files.isRegularFile(file)) {
            into.put(base.getFileName() + "/" + base.relativize(file),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
    }

    @Test
    public void aReadOnlyToolRunLeavesEveryEclipseSettingFileAsItWas() throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        McpClient client = McpClient.connect(at.url());
        List<IProject> projects = List.of(maven(), gradle(), plugin());
        // read by scripts/28k-e2e.sh, which reports whether the real m2e, Buildship and PDE natures were under test in each download
        System.out.println("jawata-fixture " + naturesHere());
        try {
            // Settle first: creating the fixtures is Eclipse writing its own files, and only what the engine does after is measured.
            String[] lastSeen = { "no answer yet" };
            try {
                Await.found("the engine sees the three projects", () -> {
                    try {
                        String found = McpClient.text(client.call("search_symbols", "{\"query\":\"Greeter\"}"));
                        lastSeen[0] = found;
                        // one p.Greeter per project; the engine prints one project's paths relative to its root, so names are not looked for
                        int seen = found.split("\"qualifiedName\":\"p\\.Greeter\"", -1).length - 1;
                        return seen >= projects.size() ? Optional.of(found) : Optional.empty();
                    } catch (Exception e) {
                        lastSeen[0] = e.toString();
                        return Optional.empty();
                    }
                });
            } catch (AssertionError notSeen) {
                throw new AssertionError(notSeen.getMessage() + "; the last answer of search_symbols was: " + lastSeen[0], notSeen);
            }
            Map<String, String> before = snapshot(projects);
            assertTrue(before.keySet().stream().anyMatch(k -> k.endsWith("/.classpath")), "the snapshot holds the projects' files: " + before.keySet());

            List<JsonNode> answers = new ArrayList<>();
            answers.add(client.call("health_check", "{}"));
            answers.add(client.call("search_symbols", "{\"query\":\"Greeter\"}"));
            answers.add(client.call("find_references", "{\"kind\":\"references\",\"symbol\":\"p.Greeter#hello\"}"));
            answers.add(client.call("inspect", "{\"kind\":\"type_members\",\"typeName\":\"p.Greeter\"}"));
            answers.add(client.call("analyze", "{\"kind\":\"type\",\"typeName\":\"p.Greeter\"}"));
            answers.add(client.call("get_diagnostics", "{}"));
            answers.add(client.call("inspect", "{\"kind\":\"project_structure\"}"));
            // the two that reach the reconciler: a change scan with refreshLocal, and a build joined
            answers.add(client.call("refresh_workspace", "{}"));
            answers.add(client.call("compile_workspace", "{}"));
            for (JsonNode answer : answers) {
                assertFalse(McpClient.failed(answer), "a read-only tool answered with an error: " + answer + " (" + naturesHere() + ")");
            }

            assertEquals(before, snapshot(projects), "the tool run changed a setting file (" + naturesHere() + ")");

            // The control: the same snapshot does see a settings file change, so "unchanged" above is not a snapshot that cannot see.
            write(projects.get(0), ".settings/org.eclipse.m2e.core.prefs", "activeProfiles=changed\neclipse.preferences.version=1\n");
            assertFalse(before.equals(snapshot(projects)), "the snapshot did not notice a changed settings file");
        } finally {
            WorkspaceProjects.deleteQuietly(projects.toArray(IProject[]::new));
        }
    }
}
