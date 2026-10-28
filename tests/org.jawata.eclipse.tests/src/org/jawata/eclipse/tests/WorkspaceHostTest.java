package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.jawata.eclipse.host.EclipseReconciler;
import org.jawata.eclipse.host.EclipseWorkspace;
import org.jawata.eclipse.host.EclipseWrites;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.HostWorkspace.ProjectFolder;
import org.jawata.spi.host.SyncReport;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 16, step 16.2: the workspace, the reconciler and the writes of the Eclipse host, against the real workspace Eclipse runs
 * these tests in (contract §4.2, §6.2): Eclipse owns the workspace, files changed outside it are noticed and reported once, a rebuild
 * names only what it built, and a write is refused by name until Stage 18.
 */
public class WorkspaceHostTest {

    private static final IWorkspace WORKSPACE = ResourcesPlugin.getWorkspace();

    private static IProject newProject(String prefix) throws CoreException {
        IProject project = WORKSPACE.getRoot().getProject(prefix + UUID.randomUUID());
        project.create(null);
        project.open(null);
        return project;
    }

    private static void deleteQuietly(IProject project) {
        try {
            project.delete(true, true, null);
        } catch (CoreException e) {
            // a project that cannot be removed is left to the workspace's own cleanup
        }
    }

    @Test
    public void theFoldersAreTheWorkspacesProjectsWithTheirOpenState() throws Exception {
        IProject open = newProject("jawata-open-");
        IProject closed = newProject("jawata-closed-");
        try {
            closed.close(null);
            EclipseWorkspace workspace = new EclipseWorkspace(WORKSPACE.getRoot());

            List<ProjectFolder> folders = workspace.folders();

            ProjectFolder seenOpen = folders.stream().filter(f -> f.name().equals(open.getName())).findFirst().orElseThrow();
            ProjectFolder seenClosed = folders.stream().filter(f -> f.name().equals(closed.getName())).findFirst().orElseThrow();
            assertTrue(seenOpen.open());
            assertFalse(seenClosed.open(), "a closed project is listed and marked closed");
            assertEquals(Path.of(open.getLocation().toOSString()), seenOpen.path());
            assertFalse(workspace.ownsWorkspace(), "Eclipse owns the workspace; the engine follows it");
        } finally {
            deleteQuietly(open);
            deleteQuietly(closed);
        }
    }

    @Test
    public void everyWriteIsRefusedByName() {
        EclipseWrites writes = new EclipseWrites("org.jawata.eclipse");

        Optional<String> refusal = writes.refusal(List.of());
        assertTrue(refusal.isPresent(), "the acceptance step is told before a tool runs");
        assertTrue(refusal.get().contains(EclipseWrites.WRITES_NOT_AVAILABLE), refusal.get());

        CoreException thrown = assertThrows(CoreException.class, () -> writes.perform(null, null));
        assertTrue(thrown.getStatus().getMessage().contains(EclipseWrites.WRITES_NOT_AVAILABLE), thrown.getStatus().getMessage());
    }

    @Test
    public void aFileChangedOutsideEclipseIsReportedOnceAndThenNotAgain() throws Exception {
        IProject project = newProject("jawata-sync-");
        try (EclipseReconciler reconciler = new EclipseReconciler(WORKSPACE)) {
            reconciler.syncBeforeCall();
            Path file = Path.of(project.getLocation().toOSString()).resolve("Outside.txt");

            Files.writeString(file, "written by something other than Eclipse\n");
            SyncReport first = reconciler.syncBeforeCall();
            SyncReport second = reconciler.syncBeforeCall();

            assertTrue(first.refreshedPaths().contains(file.toString()), "the file written outside Eclipse is named: " + first.refreshedPaths());
            assertTrue(first.reconciled());
            assertFalse(second.refreshedPaths().contains(file.toString()), "and it is not reported a second time");
        } finally {
            deleteQuietly(project);
        }
    }

    @Test
    public void aProjectAddedToTheWorkspaceIsCounted() throws Exception {
        try (EclipseReconciler reconciler = new EclipseReconciler(WORKSPACE)) {
            reconciler.syncBeforeCall();
            IProject project = newProject("jawata-added-");
            try {
                SyncReport report = reconciler.syncBeforeCall();

                assertTrue(report.newProjects() >= 1, "the new project is counted: " + report);
            } finally {
                deleteQuietly(project);
            }
        }
    }

    @Test
    public void aRebuildNamesOnlyTheProjectsItBuilt() throws Exception {
        IProject open = newProject("jawata-build-");
        IProject closed = newProject("jawata-notbuilt-");
        try (EclipseReconciler reconciler = new EclipseReconciler(WORKSPACE)) {
            closed.close(null);

            Set<String> built = reconciler.rebuild(BuildScope.of(open.getName(), closed.getName(), "jawata-no-such-project"),
                BuildKind.FULL, null);

            assertEquals(Set.of(open.getName()), built, "a closed project and an unknown name are left out, never answered as built");
        } finally {
            deleteQuietly(open);
            deleteQuietly(closed);
        }
    }
}
