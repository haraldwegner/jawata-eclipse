package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.core.project.BuildSystem;
import org.jawata.eclipse.java.EclipseJavaBinding;
import org.jawata.eclipse.java.EclipseJdtService;
import org.jawata.eclipse.java.EclipseModelSource;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 16, step 16.3: the engine's Java model answers from the Java projects Eclipse has open, run against a real Java
 * project in the workspace Eclipse runs these tests in (contract §4.1): types, files and positions are found through Eclipse's own
 * model, a closed or non-Java project is not in view, a project added later is seen without anyone loading it, and the engine
 * cannot open a project itself.
 */
public class JavaModelHostTest {

    private static final IWorkspaceRoot ROOT = ResourcesPlugin.getWorkspace().getRoot();
    private static final String PLUGIN = "org.jawata.eclipse";

    private static Path greeter(IProject project) {
        return Path.of(project.getFile("src/p/Greeter.java").getLocation().toOSString());
    }

    private static List<String> names(IJdtService service) {
        return service.allProjects().stream().map(p -> p.javaProject().getProject().getName()).toList();
    }

    @Test
    public void onlyOpenJavaProjectsAreTheModel() throws Exception {
        IProject java = WorkspaceProjects.java("jawata-java-");
        IProject closed = WorkspaceProjects.java("jawata-javaclosed-");
        IProject plain = WorkspaceProjects.open("jawata-plain-");
        try (EclipseJdtService service = new EclipseJdtService(ROOT, 30)) {
            closed.close(null);

            assertTrue(names(service).contains(java.getName()), "an open Java project is in view: " + names(service));
            assertFalse(names(service).contains(closed.getName()), "a closed project is not analysed: " + names(service));
            assertFalse(names(service).contains(plain.getName()), "a project without the Java nature is not in view: " + names(service));
            assertTrue(service.defaultProjectKey().isPresent(), "with a Java project open there is a default to answer for");

            LoadedProject described = service.allProjects().stream()
                .filter(p -> p.javaProject().getProject().getName().equals(java.getName())).findFirst().orElseThrow();
            assertEquals(1, described.sourceFileCount());
            assertEquals(1, described.packageCount());
            assertEquals(BuildSystem.ECLIPSE, described.buildSystem(), "a project with only Eclipse's own files is classified by them");
            assertEquals(Path.of(java.getLocation().toOSString()), described.projectRoot());
        } finally {
            WorkspaceProjects.deleteQuietly(java, closed, plain);
        }
    }

    @Test
    public void typesFilesAndPositionsAreFoundThroughEclipsesOwnModel() throws Exception {
        IProject java = WorkspaceProjects.java("jawata-find-");
        try (EclipseJdtService service = new EclipseJdtService(ROOT, 30)) {
            Path file = greeter(java);

            IType type = service.findType("p.Greeter");
            assertNotNull(type, "the class is found by its qualified name");
            assertEquals("Greeter", type.getElementName());
            ICompilationUnit unit = service.getCompilationUnit(file);
            assertNotNull(unit, "its file resolves to a compilation unit");
            assertTrue(service.getAllJavaFiles().contains(file), "the file is listed: " + service.getAllJavaFiles());

            IType atName = service.getTypeAtPosition(file, 2, 14);
            assertNotNull(atName, "a position on the class name selects the class");
            assertEquals("p.Greeter", atName.getFullyQualifiedName());
            IJavaElement inMethod = service.getElementAtPosition(file, 4, 10);
            assertNotNull(inMethod, "a position inside a method selects an element");

            int offset = service.getOffset(unit, 3, 11);
            assertEquals(3, service.getLineNumber(unit, offset));
            assertEquals(11, service.getColumnNumber(unit, offset));
            assertEquals("public String hello() {", service.getContextLine(unit, offset));
        } finally {
            WorkspaceProjects.deleteQuietly(java);
        }
    }

    @Test
    public void aProjectAddedLaterIsSeenWithoutAnyoneLoadingIt() throws Exception {
        try (EclipseJdtService service = new EclipseJdtService(ROOT, 30)) {
            int before = service.projectKeys().size();
            IProject added = WorkspaceProjects.java("jawata-later-");
            try {
                assertTrue(Await.until(() -> names(service).contains(added.getName())),
                    "the project Eclipse gained is in view on the next question: " + names(service));
                assertTrue(service.projectKeys().size() > before);
            } finally {
                WorkspaceProjects.deleteQuietly(added);
            }
            assertTrue(Await.until(() -> !names(service).contains(added.getName())), "and out of view once it is gone: " + names(service));
        }
    }

    @Test
    public void theModelSourceHandsOverTheModelAndRefusesToOpenByName() throws Exception {
        try (EclipseJdtService service = new EclipseJdtService(ROOT, 30)) {
            EclipseModelSource source = new EclipseModelSource(service, PLUGIN);

            assertSame(service, source.current());
            CoreException refused = assertThrows(CoreException.class, () -> source.open(Path.of(System.getProperty("java.io.tmpdir"))));
            assertTrue(refused.getStatus().getMessage().contains(EclipseModelSource.HOST_OWNS_WORKSPACE), refused.getStatus().getMessage());
        }
    }

    @Test
    public void theBindingNamesTheProjectsRuntimeAndSaysEclipseDidTheUiSetup() throws Exception {
        IProject java = WorkspaceProjects.java("jawata-jre-");
        try (EclipseJdtService service = new EclipseJdtService(ROOT, 30)) {
            EclipseModelSource source = new EclipseModelSource(service, PLUGIN);
            EclipseJavaBinding binding = new EclipseJavaBinding(source);

            assertSame(source, binding.model());
            assertTrue(binding.providesJdtUiSetup(), "Eclipse has done the JDT UI setup, so the engine does none");
            IJavaProject project = JavaCore.create(java);
            Path home = binding.runtimes().jreFor(project);
            assertTrue(Files.isDirectory(home), "the runtime named is a real directory: " + home);
        } finally {
            WorkspaceProjects.deleteQuietly(java);
        }
    }
}
