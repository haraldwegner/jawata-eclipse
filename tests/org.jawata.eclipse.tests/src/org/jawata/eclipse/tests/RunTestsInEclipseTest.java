package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 26, finding F1 of the dogfood (spec D4: "tests run through jawata's runner for gates"): the engine inside Eclipse runs a JUnit
 * class of a workspace project through {@code run_tests}. Before the plug-in carried the runner jars the call was refused with "Neither
 * jawata.tools.dir nor jawata.dist.root is set"; the opt-in dogfood showed it on jawata-mcp's own tests, and this keeps it in every build, in
 * both downloads, so the tools folder is employed by something that runs in the default gate and not only built.
 */
public class RunTestsInEclipseTest {

    private static final String TEST = "package p;\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\nimport org.junit.jupiter.api.Test;\n\n"
        + "public class GreeterTest {\n    @Test\n    void greets() {\n        assertEquals(\"hi\", new Greeter().hello());\n    }\n}\n";

    @Test
    public void runTestsRunsAJUnitClassOfAWorkspaceProjectInsideEclipse() throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        McpClient client = McpClient.connect(at.url());
        IProject project = WorkspaceProjects.java("jawata-runtests-");
        try {
            // JUnit's console launcher jar from the plug-in's own tools folder carries Jupiter, so the fixture needs nothing else
            Path tools = Path.of(DefaultScope.INSTANCE.getNode(EclipseEngine.PLUGIN_ID).get(SettingKeys.TOOLS_DIR.name(), "/nonexistent"));
            Path jupiter;
            try (Stream<Path> files = Files.list(tools)) {
                jupiter = files.filter(f -> f.getFileName().toString().startsWith("junit-platform-console-standalone-")).findFirst().orElseThrow();
            }
            IJavaProject java = JavaCore.create(project);
            List<IClasspathEntry> entries = new ArrayList<>(List.of(java.getRawClasspath()));
            entries.add(JavaCore.newLibraryEntry(new org.eclipse.core.runtime.Path(jupiter.toString()), null, null));
            java.setRawClasspath(entries.toArray(IClasspathEntry[]::new), null);
            project.getFolder("src/p").getFile("GreeterTest.java").create(new ByteArrayInputStream(TEST.getBytes(StandardCharsets.UTF_8)), true, null);
            ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
            Await.found("the engine sees the test class", () -> {
                try {
                    return McpClient.text(client.call("search_symbols", "{\"query\":\"GreeterTest\"}")).contains("p.GreeterTest")
                        ? Optional.of(true) : Optional.empty();
                } catch (Exception e) {
                    return Optional.empty();
                }
            });
            // the framework is named: the engine recognises JUnit by the Jupiter jars on the class path, and here it is inside the console launcher
            JsonNode answer = client.call("run_tests", "{\"framework\":\"junit5\",\"scope\":{\"kind\":\"class\",\"typeName\":\"p.GreeterTest\"}}");
            String text = McpClient.text(answer);
            assertTrue(text.contains("\"passed\":1") && text.contains("\"failed\":0") && text.contains("\"evidenceFinalized\":true"),
                "run_tests ran the JUnit class inside Eclipse and it passed: " + text);
        } finally {
            WorkspaceProjects.deleteQuietly(project);
        }
    }
}
