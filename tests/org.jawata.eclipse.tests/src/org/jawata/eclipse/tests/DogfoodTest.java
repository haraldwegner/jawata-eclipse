package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 26 (contract section 18, ring 3): jawata-mcp developed inside Eclipse with the plug-in. The bundles of a scratch clone of
 * jawata-mcp at the released tag become Java projects of this Eclipse's workspace, and the engine inside Eclipse is worked over MCP the way
 * an agent works it: every one of the 44 tools is asked something about jawata-mcp's own code, a refactoring is applied and undone, a test
 * of jawata-mcp is run, and a debug session and a profile are taken of that run. What each tool answered goes to the report file.
 *
 * <p>It needs the clone ({@code JAWATA_DOGFOOD_REPO}), the engine dist built from it ({@code JAWATA_DOGFOOD_DIST}) and a folder for the report
 * ({@code JAWATA_DOGFOOD_OUT}); {@code scripts/28k-dogfood.sh} supplies all three. Every default build leaves the class out.
 */
public class DogfoodTest {

    /** The bundles of jawata-mcp that become projects, in the order they depend on one another. */
    private static final List<String> BUNDLES = List.of("org.jawata.spi", "org.jawata.core", "org.jawata.catalogue", "org.jawata.mcp",
        "org.jawata.resident", "org.jawata.standin");

    /** The test bundles of jawata-mcp, which need the bundles above and JUnit. */
    private static final List<String> TEST_BUNDLES = List.of("org.jawata.core.tests", "org.jawata.mcp.tests");

    private static final String LOAD_PROJECT_TOOL = "org.jawata.mcp.tools.LoadProjectTool";

    /** The 44 tools the engine of this release publishes (jawata-mcp build/28k-golden-tools.json); every one must be asked. */
    private static final String PUBLISHED_TOOLS = "health_check search_symbols go_to_definition find_references inspect analyze get_at_position "
        + "get_diagnostics validate_syntax get_call_hierarchy find_field_writes find_tests find_pattern_usages find_quality_issue "
        + "find_string_literals find_duplicate_code find_modernization load_project project field rename_symbol organize_imports "
        + "delete_anonymous_implementation extract inline move hierarchy generate refactor_to_pattern change_method_signature data "
        + "apply_cleanup compile_workspace run_tests debug profile hire refresh_workspace apply_null_annotations dependency format "
        + "quick_fix refactoring experience";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void jawataMcpIsDevelopedInsideEclipseAndEveryToolAnswers() throws Exception {
        String repoName = System.getenv("JAWATA_DOGFOOD_REPO");
        String distName = System.getenv("JAWATA_DOGFOOD_DIST");
        String outName = System.getenv("JAWATA_DOGFOOD_OUT");
        assertNotNull(repoName, "JAWATA_DOGFOOD_REPO names the jawata-mcp clone: run scripts/28k-dogfood.sh, which sets it");
        assertNotNull(distName, "JAWATA_DOGFOOD_DIST names the engine dist: run scripts/28k-dogfood.sh");
        assertNotNull(outName, "JAWATA_DOGFOOD_OUT names the report folder: run scripts/28k-dogfood.sh");
        Path repo = Path.of(repoName);
        Path dist = Path.of(distName);
        Path out = Path.of(outName);

        List<IProject> projects = importBundles(repo, dist);
        System.out.println("jawata-dogfood imported " + projects.size() + " projects");

        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        McpClient client = McpClient.connect(at.url());
        Report report = new Report(client, out.resolve("dogfood-report.tsv"));

        // the engine sees the projects once Eclipse has built its index of them
        Await.found("the engine finds jawata-mcp's own LoadProjectTool", () -> {
            try {
                return McpClient.text(client.call("search_symbols", "{\"query\":\"LoadProjectTool\",\"kind\":\"Class\"}")).contains("LoadProjectTool")
                    ? Optional.of(true) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        });

        try {
            probeEveryTool(report, repo);
            refactorAndUndo(report, repo);
            runTestsAndDebug(report, repo, dist);
        } finally {
            report.write();
        }
        List<String> missing = new ArrayList<>(List.of(PUBLISHED_TOOLS.split(" ")));
        missing.removeAll(report.toolsAsked());
        assertTrue(missing.isEmpty(), "tools the dogfood never asked: " + missing);
        assertTrue(report.brokenAnswers().isEmpty(), "answers that show a broken engine, not a refusal: " + report.brokenAnswers());
    }

    private static Map<String, Object> args(Object... keysAndValues) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            arguments.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return arguments;
    }

    /**
     * One question per tool, each about jawata-mcp's own LoadProjectTool or the code around it. Every change is staged
     * ({@code auto_apply=false}) here; the one applied change is {@link #refactorAndUndo}. A tool that refuses a question
     * (a staged move of an interface method, say) is a recorded answer, not a failure: the report shows which refused and why.
     */
    private static void probeEveryTool(Report report, Path repo) throws Exception {
        String file = repo.resolve("org.jawata.mcp/src/org/jawata/mcp/tools/LoadProjectTool.java").toString();
        report.record("health_check", args());
        report.record("search_symbols", args("query", "LoadProjectTool", "kind", "Class"));
        report.record("find_references", args("kind", "references", "symbol", LOAD_PROJECT_TOOL, "maxResults", 3));
        report.record("inspect", args("kind", "type_members", "typeName", LOAD_PROJECT_TOOL));
        report.record("go_to_definition", args("filePath", file, "line", 86, "column", 19));
        report.record("analyze", args("kind", "type", "typeName", LOAD_PROJECT_TOOL));
        report.record("get_at_position", args("kind", "method", "filePath", file, "line", 84, "column", 24));
        report.record("get_diagnostics", args("filePath", file));
        report.record("validate_syntax", args("content", "class X { void m( }"));
        report.record("get_call_hierarchy", args("direction", "incoming", "symbol", LOAD_PROJECT_TOOL + "#getName"));
        report.record("find_field_writes", args("symbol", LOAD_PROJECT_TOOL + "#model"));
        report.record("find_tests", args("pattern", "*ErrorInfo*"));
        report.record("find_pattern_usages", args("kind", "instantiation", "query", LOAD_PROJECT_TOOL));
        report.record("find_quality_issue", args("kind", "long_method", "filePath", file));
        report.record("find_string_literals", args("query", "load_project", "filePath", file));
        report.record("find_duplicate_code", args("summary", true, "minTokens", 100));
        report.record("find_modernization", args("kind", "optional", "maxResults", 5));
        report.record("load_project", args("projectPath", "/tmp"));
        report.record("project", args("action", "list"));
        report.record("field", args("action", "pile", "limit", 3));
        report.record("rename_symbol", args("symbol", LOAD_PROJECT_TOOL + "#log", "newName", "logger", "auto_apply", false));
        report.record("organize_imports", args("filePath", file, "auto_apply", false));
        report.record("delete_anonymous_implementation", args("filePath", file, "line", 84, "column", 24, "auto_apply", false));
        report.record("extract", args("kind", "variable", "filePath", file, "startLine", 136, "startColumn", 35, "endLine", 136, "endColumn", 93,
            "variableName", "shownPackages", "auto_apply", false));
        report.record("inline", args("kind", "variable", "filePath", file, "line", 95, "column", 17, "auto_apply", false));
        report.record("move", args("kind", "method", "symbol", LOAD_PROJECT_TOOL + "#getName", "auto_apply", false));
        report.record("hierarchy", args("direction", "up", "symbol", LOAD_PROJECT_TOOL + "#getName", "auto_apply", false));
        report.record("generate", args("kind", "tostring", "filePath", file, "line", 25, "column", 13, "fields", List.of("model", "workspace"),
            "auto_apply", false));
        report.record("refactor_to_pattern", args("kind", "decompose_conditional", "filePath", file, "line", 98, "column", 12, "conditionName",
            "pathMissing", "auto_apply", false));
        report.record("change_method_signature", args("kind", "change_signature", "symbol", LOAD_PROJECT_TOOL + "#getDescription", "newName",
            "describe", "auto_apply", false));
        report.record("data", args("kind", "encapsulate_field", "symbol", LOAD_PROJECT_TOOL + "#model", "auto_apply", false));
        report.record("apply_cleanup", args("kind", "add_final", "filePath", file, "auto_apply", false));
        report.record("compile_workspace", args("summary", true));
        report.record("hire", args("hirer", "dogfood", "seat", "none", "seatFile", "/nonexistent/seat.md", "brief", "none", "steps", List.of(),
            "workingDirectory", repo.toString()));
        report.record("refresh_workspace", args());
        report.record("apply_null_annotations", args("kind", "add", "symbol", LOAD_PROJECT_TOOL + "#getName", "nullness", "nonnull",
            "allowPublicApi", true, "auto_apply", false));
        report.record("dependency", args("action", "find_unused"));
        report.record("format", args("scope", args("kind", "file", "filePath", file), "dryRun", true));
        report.record("quick_fix", args("action", "suggest_imports", "typeName", "List"));
        report.record("refactoring", args("action", "inspect", "changeId", "none"));
        report.record("experience", args("kind", "stats"));
        // memory's code anchors resolve inside Eclipse: a fact recorded against a class of the clone comes back by that class, its pointer
        // resolved to the code (the test run keeps the store in memory, jawata.experience.store=memory in the test pom, so the person's own is untouched)
        JsonNode stored = report.record("experience", args("kind", "record", "type", "domain_fact", "symbol", LOAD_PROJECT_TOOL,
            "summary", "load_project is refused when the host owns the workspace, as inside Eclipse, and answers HOST_OWNS_WORKSPACE."));
        report.record("experience", args("kind", "promote", "id", stored.findValue("id").asText()));
        JsonNode recalled = report.record("experience", args("kind", "recall", "lane", "domain", "symbol", LOAD_PROJECT_TOOL));
        assertTrue(recalled.toString().contains("HOST_OWNS_WORKSPACE"), "the fact recorded against LoadProjectTool comes back by that class: " + recalled);
    }

    /**
     * A rename of the class's private logger is applied for real, Eclipse's workspace writes it to the clone, the workspace still compiles
     * as it did, and the engine's own undo puts every byte back (spec D2: an accepted refactoring is undone).
     */
    private static void refactorAndUndo(Report report, Path repo) throws Exception {
        Path file = repo.resolve("org.jawata.mcp/src/org/jawata/mcp/tools/LoadProjectTool.java");
        String before = Files.readString(file);
        assertTrue(git(repo, "status", "--porcelain", "--", "org.jawata.mcp/src").isEmpty(), "the clone's sources are as the tag has them");
        int errorsBefore = report.record("compile_workspace", args("summary", true)).findValue("errorCount").asInt(-1);
        // The first field the engine will rename is the one applied; each refusal stays in the report with the engine's reason.
        String newName = "";
        String undoId = "";
        for (String[] candidate : List.of(new String[] { "workspace", "hostWorkspace" }, new String[] { "model", "modelSource" },
            new String[] { "log", "logger" })) {
            JsonNode applied = report.record("rename_symbol", args("symbol", LOAD_PROJECT_TOOL + "#" + candidate[0], "newName", candidate[1]));
            JsonNode handle = applied.findValue("undoChangeId");
            if (handle != null) {
                newName = candidate[1];
                undoId = handle.asText("");
                break;
            }
        }
        String during = Files.readString(file);
        assertTrue(!newName.isEmpty() && during.contains(newName) && !during.equals(before), "the rename reached the file on disk");
        assertTrue(!git(repo, "status", "--porcelain", "--", "org.jawata.mcp/src").isEmpty(), "git sees the change");
        int errorsAfter = report.record("compile_workspace", args("summary", true)).findValue("errorCount").asInt(-2);
        System.out.println("jawata-dogfood rename: compile errors before " + errorsBefore + ", after " + errorsAfter);
        assertEquals(errorsBefore, errorsAfter, "the rename added no compile error");
        assertTrue(!undoId.isEmpty(), "the rename answered an undo handle");
        report.record("refactoring", args("action", "undo", "undoChangeId", undoId));
        assertEquals(before, Files.readString(file), "the undo restored every byte of the file");
        assertTrue(git(repo, "status", "--porcelain", "--", "org.jawata.mcp/src").isEmpty(), "git sees no change after the undo");
    }

    /**
     * A test of jawata-mcp is run through the engine inside Eclipse (the engine's own check of its 44 published tools, and a small one),
     * then the small one is started under the debugger with a breakpoint in jawata-mcp's ErrorInfo, a variable is read at the stop, and
     * the stopped JVM is profiled.
     */
    private static void runTestsAndDebug(Report report, Path repo, Path dist) throws Exception {
        report.record("run_tests", args("scope", args("kind", "class", "typeName", "org.jawata.mcp.TheFortyFourPublishedToolsTest")));
        report.record("run_tests", args("scope", args("kind", "class", "typeName", "org.jawata.mcp.models.ErrorInfoTest")));
        report.record("run_tests", args("scope", args("kind", "class", "typeName", "org.jawata.core.ProjectKeysTest")));
        report.record("debug", args("action", "discover"));
        List<String> classpath = new ArrayList<>();
        for (String bundle : concat(BUNDLES, TEST_BUNDLES)) {
            classpath.add(repo.resolve(bundle).resolve("bin").toString());
        }
        for (String folder : List.of("bundles", "tools")) {
            try (Stream<Path> files = Files.list(dist.resolve(folder))) {
                // the console launcher in tools/ carries its own platform and Jupiter; the older JUnit jars of test-bundles/ beside it make it
                // fail with NoSuchMethodError, so that folder is left out of the debug target's class path
                files.filter(f -> f.getFileName().toString().endsWith(".jar") && !f.getFileName().toString().startsWith("org.jawata."))
                    .filter(f -> !f.getFileName().toString().startsWith("junit-platform-") || f.getFileName().toString().contains("console-standalone"))
                    .filter(f -> !folder.equals("tools") || f.getFileName().toString().contains("console-standalone"))
                    .filter(f -> !f.getFileName().toString().startsWith("junit-jupiter-engine")).sorted().forEach(f -> classpath.add(f.toString()));
            }
        }
        JsonNode launched = report.record("debug", args("action", "launch", "mainClass", "org.junit.platform.console.ConsoleLauncher",
            "classpath", String.join(java.io.File.pathSeparator, classpath), "args",
            List.of("execute", "--disable-banner", "--select-class", "org.jawata.mcp.models.ErrorInfoTest"), "workingDirectory", repo.toString()));
        JsonNode sessionNode = launched.findValue("sessionId");
        if (sessionNode == null) {
            return;
        }
        String session = sessionNode.asText();
        try {
            report.record("debug", args("action", "breakpoint_set", "sessionId", session, "kind", "method", "className",
                "org.jawata.mcp.models.ErrorInfo", "method", "getCode"));
            report.record("debug", args("action", "resume", "sessionId", session));
            JsonNode stopped = report.record("debug", args("action", "wait", "sessionId", session, "timeoutMillis", 90_000));
            JsonNode thread = stopped.findValue("threadId");
            if (thread != null) {
                report.record("debug", args("action", "snapshot", "sessionId", session, "threadId", thread.asLong()));
                report.record("debug", args("action", "evaluate", "sessionId", session, "threadId", thread.asLong(), "expression", "this.code"));
            }
            report.record("profile", args("action", "threads", "sessionId", session, "maxFrames", 5));
            report.record("profile", args("action", "gc", "sessionId", session));
        } finally {
            report.record("debug", args("action", "detach", "sessionId", session));
        }
    }

    private static String git(Path repo, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + " failed: " + out);
        }
        return out.strip();
    }

    /**
     * Makes each bundle of the clone a Java project located in the clone: source folder {@code src}, output {@code bin}, Eclipse's default
     * runtime, every jar of the dist beside the earlier bundles as projects. The test bundles also get JUnit, which the dist keeps apart.
     */
    private static List<IProject> importBundles(Path repo, Path dist) throws Exception {
        List<Path> jars = new ArrayList<>();
        for (String folder : List.of("bundles", "test-bundles")) {
            try (Stream<Path> files = Files.list(dist.resolve(folder))) {
                files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
            }
        }
        // the jawata bundles are the projects themselves; a jar of the same classes beside them would answer in their place
        jars.removeIf(jar -> jar.getFileName().toString().startsWith("org.jawata."));
        // the logging and framework jars the dist keeps beside its folders, which the engine's code is written against
        try (Stream<Path> files = Files.list(dist)) {
            files.filter(f -> f.getFileName().toString().startsWith("slf4j-api") || f.getFileName().toString().startsWith("org.eclipse.osgi"))
                .sorted().forEach(jars::add);
        }
        // the resident bundle keeps the Gradle tooling library in its own lib/ folder
        try (Stream<Path> files = Files.list(repo.resolve("org.jawata.resident").resolve("lib"))) {
            files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        List<IProject> imported = new ArrayList<>();
        List<String> earlier = new ArrayList<>();
        for (String bundle : concat(BUNDLES, TEST_BUNDLES)) {
            imported.add(importBundle(repo.resolve(bundle), bundle, jars, earlier));
            earlier.add(bundle);
        }
        ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
        return imported;
    }

    private static IProject importBundle(Path location, String name, List<Path> jars, List<String> earlier) throws CoreException, IOException {
        IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(name);
        description.setLocationURI(location.toUri());
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        project.create(description, null);
        project.open(null);
        // the nature is set on the open project, as Eclipse's own wizard does: only then does Eclipse configure it and add the Java builder
        IProjectDescription opened = project.getDescription();
        opened.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(opened, null);
        List<IClasspathEntry> entries = new ArrayList<>();
        entries.add(JavaCore.newSourceEntry(project.getFullPath().append("src")));
        entries.add(JavaCore.newContainerEntry(org.eclipse.core.runtime.Path.fromPortableString("org.eclipse.jdt.launching.JRE_CONTAINER")));
        for (String other : earlier) {
            entries.add(JavaCore.newProjectEntry(ResourcesPlugin.getWorkspace().getRoot().getProject(other).getFullPath()));
        }
        for (Path jar : jars) {
            IPath library = new org.eclipse.core.runtime.Path(jar.toString());
            entries.add(JavaCore.newLibraryEntry(library, null, null));
        }
        Files.createDirectories(location.resolve("bin"));
        IJavaProject java = JavaCore.create(project);
        java.setRawClasspath(entries.toArray(IClasspathEntry[]::new), project.getFolder("bin").getFullPath(), null);
        return project;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** What each tool answered, kept in the order asked and written as one tab-separated line per call. */
    private static final class Report {
        private final McpClient client;
        private final Path file;
        private final List<String> lines = new ArrayList<>();
        private final Set<String> asked = new LinkedHashSet<>();
        private final List<String> broken = new ArrayList<>();

        Report(McpClient client, Path file) {
            this.client = client;
            this.file = file;
        }

        /**
         * Calls the tool, keeps its answer in the report and returns what the tool's text holds as JSON (an empty object when the text is
         * not JSON), so a later call can use a value from it.
         */
        JsonNode record(String tool, Map<String, Object> arguments) throws Exception {
            String json = JSON.writeValueAsString(arguments);
            JsonNode answer = client.call(tool, json);
            String text = McpClient.text(answer);
            boolean failed = McpClient.failed(answer);
            asked.add(tool);
            if (failed && (text.contains("INTERNAL_ERROR") || text.contains("UNKNOWN_TOOL") || text.isEmpty())) {
                broken.add(tool + ": " + (text.isEmpty() ? answer.toString() : text.substring(0, Math.min(300, text.length()))));
            }
            lines.add(tool + "\t" + (failed ? "REFUSED-OR-FAILED" : "answered") + "\t" + json + "\t" + text.replace('\n', ' ').replace('\t', ' '));
            System.out.println("jawata-dogfood " + tool + " " + (failed ? "failed/refused" : "answered") + ": " + text.substring(0, Math.min(200, text.length())));
            try {
                return JSON.readTree(text);
            } catch (IOException notJson) {
                return JSON.createObjectNode();
            }
        }

        /** The tools asked so far, once each. */
        Set<String> toolsAsked() {
            return asked;
        }

        /** The answers that show an engine fault (an internal error, an unknown tool, no text at all) as opposed to a refusal. */
        List<String> brokenAnswers() {
            return broken;
        }

        void write() throws IOException {
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
    }
}
