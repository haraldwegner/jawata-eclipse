package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Stage 25a, step 25a.6, "the Java runtime asked at the child-JVM sites" (ledger 28k-A19-1, 28k-C5-15): the engine starts a program for a
 * project on the runtime Eclipse says that project runs on, and on its own runtime when no project is named. The proof is the java a started
 * program was launched by, read from the operating system (Linux). The project's runtime is a second path to the same JDK, so the program
 * starts either way and the only difference is which path Eclipse's setting for the project names.
 */
public class ChildJvmRuntimeTest {

    private static final String SPIN = "package p;\n\npublic class Spin {\n    public static void main(String[] args) throws Exception {\n"
        + "        Thread.sleep(60000);\n    }\n}\n";
    private static final String STANDARD_VM = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType";

    /** The java a process was started by, as it was named when it was started (the first word of its command line). */
    private static String startedBy(long pid) throws Exception {
        String cmdline = Files.readString(Path.of("/proc/" + pid + "/cmdline"), StandardCharsets.ISO_8859_1);
        return cmdline.substring(0, cmdline.indexOf('\0'));
    }

    private static java.util.stream.Stream<ProcessHandle> spinners() {
        return ProcessHandle.current().descendants().filter(p -> p.info().arguments().map(a -> java.util.List.of(a).contains("p.Spin")).orElse(false));
    }

    /** The pid of the program the engine launched, found among the processes this Eclipse started. */
    private static long launchedPid() throws Exception {
        return Await.found("the launched program is among Eclipse's child processes", () -> spinners().mapToLong(ProcessHandle::pid).boxed().findFirst());
    }

    /** Present once the engine's Java model holds the fixture project's Greeter. */
    private static java.util.Optional<Boolean> engineSeesAGreeter(McpClient client) {
        try {
            return McpClient.text(client.call("search_symbols", "{\"query\":\"Greeter\"}")).contains("qualifiedName") ? java.util.Optional.of(true)
                : java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private static String projectKeyOf(McpClient client, IProject project) throws Exception {
        JsonNode listed = new ObjectMapper().readTree(McpClient.text(client.call("project", "{\"action\":\"list\"}")));
        for (JsonNode entry : listed.path("data").path("projects")) {
            String key = entry.path("projectKey").asText("");
            if (key.contains(project.getName()) || entry.path("path").asText("").contains(project.getName())) {
                return key;
            }
        }
        throw new AssertionError("the engine lists no project for " + project.getName() + ": " + listed);
    }

    private static String sessionOf(String answer) {
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"").matcher(answer);
        assertTrue(id.find(), "the launch answers a session: " + answer);
        return id.group(1);
    }

    @Test
    public void aProgramLaunchedForAProjectRunsOnTheRuntimeEclipseSetsThatProjectUpWithAndOtherwiseOnTheEnginesOwn() throws Exception {
        assumeTrue(Files.exists(Path.of("/proc/self/cmdline")), "the started java is read from /proc");
        Path scratch = Files.createTempDirectory("jawata-altjre-");
        Path alt = scratch.resolve("alt-jdk");
        Files.createSymbolicLink(alt, Path.of(System.getProperty("java.home")));
        IVMInstallType type = JavaRuntime.getVMInstallType(STANDARD_VM);
        assertNotNull(type, "Eclipse has its standard JRE type");
        String id = "jawata-alt-" + UUID.randomUUID();
        IVMInstall vm = type.createVMInstall(id);
        vm.setName(id);
        vm.setInstallLocation(alt.toFile());
        IProject project = WorkspaceProjects.java("jawata-jre-");
        String session = null;
        McpClient client = null;
        try {
            project.getFolder("src/p").getFile("Spin.java").create(new ByteArrayInputStream(SPIN.getBytes(StandardCharsets.UTF_8)), true, null);
            IJavaProject java = JavaCore.create(project);
            IClasspathEntry[] raw = java.getRawClasspath();
            for (int i = 0; i < raw.length; i++) {
                if (raw[i].getPath().toString().startsWith(JavaRuntime.JRE_CONTAINER)) {
                    raw[i] = JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(vm));
                }
            }
            java.setRawClasspath(raw, null);
            project.build(IncrementalProjectBuilder.FULL_BUILD, null);
            assertEquals(vm, JavaRuntime.getVMInstall(java), "Eclipse says this project runs on the second path to the JDK");
            assertTrue(project.getFile("bin/p/Spin.class").exists(), "the program is compiled");
            String classpath = project.getFolder("bin").getLocation().toOSString();

            EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
            McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
            client = McpClient.connect(at.url());
            McpClient asked = client;
            Await.found("the engine sees " + project.getName(), () -> engineSeesAGreeter(asked));
            String key = projectKeyOf(client, project);

            String forProject = McpClient.text(client.call("debug", "{\"action\":\"launch\",\"mainClass\":\"p.Spin\",\"classpath\":\""
                + classpath.replace("\\", "\\\\") + "\",\"projectKey\":\"" + key + "\"}"));
            session = sessionOf(forProject);
            assertEquals(alt.resolve("bin").resolve("java").toString(), startedBy(launchedPid()),
                "launched for the project, the program is started by the runtime Eclipse sets the project up with: " + forProject);
            client.call("debug", "{\"action\":\"detach\",\"sessionId\":\"" + session + "\"}");
            session = null;
            assertTrue(Await.until(() -> spinners().findAny().isEmpty()), "detach ended the program it launched");

            String alone = McpClient.text(client.call("debug", "{\"action\":\"launch\",\"mainClass\":\"p.Spin\",\"classpath\":\""
                + classpath.replace("\\", "\\\\") + "\"}"));
            session = sessionOf(alone);
            String started = startedBy(launchedPid());
            assertEquals(Path.of(System.getProperty("java.home"), "bin", "java").toString(), started,
                "launched for no project, it is started by the runtime the engine itself runs on: " + alone);
            assertNotEquals(alt.resolve("bin").resolve("java").toString(), started, "and not by the project's");
        } finally {
            if (session != null && client != null) {
                client.call("debug", "{\"action\":\"detach\",\"sessionId\":\"" + session + "\"}");
            }
            WorkspaceProjects.deleteQuietly(project);
            type.disposeVMInstall(id);
            Files.deleteIfExists(alt);   // the link only; the JDK it names is not touched
            Files.deleteIfExists(scratch);
        }
    }
}
