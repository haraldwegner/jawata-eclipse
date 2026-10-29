package org.jawata.eclipse.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ElementChangedEvent;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IElementChangedListener;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.core.ModelLookup;
import org.jawata.core.ProjectKeys;
import org.jawata.core.SourceListingException;
import org.jawata.core.TypeLookupException;
import org.jawata.core.host.HostPaths;
import org.jawata.core.host.HostPathsImpl;
import org.jawata.core.project.BuildSystem;
import org.jawata.core.search.SearchService;

/**
 * The engine's view of the Java model Eclipse already holds (plan Stage 16, step 16.3; contract §4.1). The headless engine builds a
 * model of its own from project folders; here the person's open Java projects ARE the model, so this answers the engine's questions
 * from them and never builds, loads or removes a project. A project is in view while it is open and has the Java nature; a closed
 * one is the workspace port's to list, not the model's to analyse.
 *
 * <p>What the engine is told is read once per change: Eclipse tells this service when its Java model changes and the next question
 * reads again, so counts and the search scope never describe a workspace that has moved on. Close it when the engine stops.</p>
 */
public final class EclipseJdtService implements IJdtService, AutoCloseable {

    /** What one reading of the Java model produced; replaced whole, never edited. */
    private static final class Snapshot {
        final List<LoadedProject> projects;
        private volatile SearchService search;

        Snapshot(List<LoadedProject> projects) {
            this.projects = projects;
        }

        SearchService search() {
            SearchService cached = search;
            if (cached == null && !projects.isEmpty()) {
                cached = new SearchService(projects.stream().map(LoadedProject::javaProject).toArray(IJavaProject[]::new));
                search = cached;
            }
            return cached;
        }
    }

    private final IWorkspaceRoot root;
    private final int timeoutSeconds;
    private final AtomicLong generation = new AtomicLong();
    private final IElementChangedListener onJavaChange = (ElementChangedEvent event) -> forget();
    private volatile Snapshot snapshot;

    /**
     * @param root the workspace root Eclipse has open
     * @param timeoutSeconds how long one engine operation may run before it is abandoned
     */
    public EclipseJdtService(IWorkspaceRoot root, int timeoutSeconds) {
        this.root = root;
        this.timeoutSeconds = timeoutSeconds;
        JavaCore.addElementChangedListener(onJavaChange, ElementChangedEvent.POST_CHANGE);
    }

    @Override
    public void close() {
        JavaCore.removeElementChangedListener(onJavaChange);
    }

    private void forget() {
        generation.incrementAndGet();
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        long seen = generation.get();
        Snapshot read = new Snapshot(readProjects());
        if (generation.get() == seen) {
            snapshot = read;
        }
        return read;
    }

    private List<LoadedProject> readProjects() {
        List<LoadedProject> read = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (IProject project : root.getProjects()) {
            IJavaProject java = javaProjectOf(project);
            if (java == null || project.getLocation() == null) {
                continue;
            }
            Path location = Path.of(project.getLocation().toOSString());
            String key = ProjectKeys.derive(location);
            if (!keys.add(key)) {
                key = ProjectKeys.disambiguate(key, location);
                keys.add(key);
            }
            read.add(describe(key, location, java));
        }
        return List.copyOf(read);
    }

    private static IJavaProject javaProjectOf(IProject project) {
        try {
            return project.isOpen() && project.hasNature(JavaCore.NATURE_ID) ? JavaCore.create(project) : null;
        } catch (CoreException e) {
            return null;
        }
    }

    private static LoadedProject describe(String key, Path location, IJavaProject java) {
        int files = 0;
        Set<String> packages = new TreeSet<>();
        try {
            for (IPackageFragmentRoot sourceRoot : java.getPackageFragmentRoots()) {
                if (sourceRoot.getKind() != IPackageFragmentRoot.K_SOURCE) {
                    continue;
                }
                for (IJavaElement child : sourceRoot.getChildren()) {
                    if (child instanceof IPackageFragment fragment && fragment.getCompilationUnits().length > 0) {
                        files += fragment.getCompilationUnits().length;
                        packages.add(fragment.getElementName());
                    }
                }
            }
        } catch (JavaModelException e) {
            // a project whose roots cannot be walked is counted as far as it was walked; problems() is what says why
        }
        return new LoadedProject(key, location, java, new SearchService(java), new HostPathsImpl(location), Instant.now(), files,
            packages.size(), List.copyOf(packages), buildSystemOf(location, java), List.of());
    }

    private static BuildSystem buildSystemOf(Path location, IJavaProject java) {
        if (Files.exists(location.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.exists(location.resolve("build.gradle")) || Files.exists(location.resolve("build.gradle.kts"))
            || Files.exists(location.resolve("settings.gradle")) || Files.exists(location.resolve("settings.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        try {
            if (java.getProject().hasNature("org.eclipse.pde.PluginNature")) {
                return BuildSystem.ECLIPSE_PDE;
            }
        } catch (CoreException e) {
            // a nature that cannot be read leaves the project classified by its files
        }
        return Files.exists(location.resolve(".classpath")) ? BuildSystem.ECLIPSE : BuildSystem.UNKNOWN;
    }

    private Optional<LoadedProject> defaultProject() {
        return snapshot().projects.stream().findFirst();
    }

    private Path workspaceLocation() {
        IPath location = root.getLocation();
        return location == null ? Path.of("").toAbsolutePath() : Path.of(location.toOSString());
    }

    // ---- the workspace's projects ----

    @Override
    public Optional<String> defaultProjectKey() {
        return defaultProject().map(LoadedProject::projectKey);
    }

    @Override
    public Optional<LoadedProject> getProject(String projectKey) {
        return snapshot().projects.stream().filter(p -> p.projectKey().equals(projectKey)).findFirst();
    }

    @Override
    public Collection<String> projectKeys() {
        return snapshot().projects.stream().map(LoadedProject::projectKey).toList();
    }

    @Override
    public Collection<LoadedProject> allProjects() {
        return snapshot().projects;
    }

    @Override
    public void reresolveWorkspace() {
        forget();
    }

    // ---- the default project, as the older tools ask for it ----

    @Override
    public HostPaths getPathUtils() {
        return defaultProject().map(LoadedProject::pathUtils).orElseGet(() -> new HostPathsImpl(workspaceLocation()));
    }

    @Override
    public Path getProjectRoot() {
        return defaultProject().map(LoadedProject::projectRoot).orElseGet(this::workspaceLocation);
    }

    @Override
    public IJavaProject getJavaProject() {
        return defaultProject().map(LoadedProject::javaProject).orElse(null);
    }

    @Override
    public SearchService getSearchService() {
        return snapshot().search();
    }

    @Override
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public <T> T executeWithTimeout(Callable<T> operation, String operationName) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(operation);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(operationName + " timed out after " + timeoutSeconds + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(operationName + " failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(operationName + " was interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- files, positions and types ----

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        ICompilationUnit unit = lookupAcrossProjects(filePath);
        if (unit != null) {
            return unit;
        }
        Path real = toRealQuietly(filePath);
        return real.equals(filePath) ? null : lookupAcrossProjects(real);
    }

    private ICompilationUnit lookupAcrossProjects(Path filePath) {
        for (LoadedProject project : snapshot().projects) {
            ICompilationUnit unit = ModelLookup.lookupCompilationUnit(project.javaProject(), filePath);
            if (unit != null) {
                return unit;
            }
        }
        return null;
    }

    private static Path toRealQuietly(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException unresolvable) {
            return path;
        }
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        ICompilationUnit unit = getCompilationUnit(filePath);
        if (unit == null) {
            return null;
        }
        try {
            if (!unit.isOpen()) {
                unit.open(null);
            }
            int offset = getOffset(unit, line, column);
            IJavaElement[] selected = unit.codeSelect(offset, 0);
            return selected.length > 0 ? selected[0] : unit.getElementAt(offset);
        } catch (JavaModelException e) {
            return null;
        }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement element = getElementAtPosition(filePath, line, column);
        if (element instanceof IType type) {
            return type;
        }
        return element == null ? null : (IType) element.getAncestor(IJavaElement.TYPE);
    }

    @Override
    public IType findType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        JavaModelException failed = null;
        for (LoadedProject project : snapshot().projects) {
            try {
                IType type = lookupType(project.javaProject(), typeName);
                if (type != null) {
                    return type;
                }
            } catch (JavaModelException e) {
                failed = failed == null ? e : failed;
            }
        }
        if (failed != null) {
            throw new TypeLookupException(typeName, failed);
        }
        return null;
    }

    private static IType lookupType(IJavaProject java, String typeName) throws JavaModelException {
        IType type = java.findType(typeName);
        if (type != null) {
            return type;
        }
        for (IPackageFragmentRoot sourceRoot : java.getPackageFragmentRoots()) {
            if (sourceRoot.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            for (IJavaElement child : sourceRoot.getChildren()) {
                if (child instanceof IPackageFragment fragment) {
                    for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                        for (IType declared : unit.getTypes()) {
                            if (declared.getElementName().equals(typeName)) {
                                return declared;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Every open Java project that declares the type in its own sources (S19-65), not the first that can resolve it: a project also
     * resolves the types of the projects it depends on, and those are not its own. A project that cannot answer stays in the list,
     * because "the lookup failed" must not read as "it is not there".
     */
    @Override
    public List<String> projectsHoldingType(String typeName) {
        List<String> holders = new ArrayList<>();
        if (typeName == null || typeName.isBlank()) {
            return holders;
        }
        for (LoadedProject project : snapshot().projects) {
            IJavaProject java = project.javaProject();
            try {
                IType type = lookupType(java, typeName);
                if (type == null && typeName.indexOf('$') >= 0) {
                    type = lookupType(java, typeName.replace('$', '.'));
                }
                if (type != null && !type.isBinary() && java.equals(type.getJavaProject())) {
                    holders.add(project.projectKey());
                }
            } catch (JavaModelException e) {
                holders.add(project.projectKey());
            }
        }
        holders.sort(null);
        return holders;
    }

    /** Every open Java project with a source package of that name, or below it, that holds at least one source file (S19-65). */
    @Override
    public List<String> projectsHoldingPackage(String packageName) {
        List<String> holders = new ArrayList<>();
        if (packageName == null || packageName.isBlank()) {
            return holders;
        }
        for (LoadedProject project : snapshot().projects) {
            try {
                if (declaresPackage(project.javaProject(), packageName)) {
                    holders.add(project.projectKey());
                }
            } catch (JavaModelException e) {
                holders.add(project.projectKey());
            }
        }
        holders.sort(null);
        return holders;
    }

    private static boolean declaresPackage(IJavaProject java, String packageName) throws JavaModelException {
        for (IPackageFragmentRoot sourceRoot : java.getPackageFragmentRoots()) {
            if (sourceRoot.getKind() != IPackageFragmentRoot.K_SOURCE || !java.equals(sourceRoot.getJavaProject())) {
                continue;
            }
            for (IJavaElement child : sourceRoot.getChildren()) {
                if (child instanceof IPackageFragment fragment) {
                    String name = fragment.getElementName();
                    if ((name.equals(packageName) || name.startsWith(packageName + ".")) && fragment.getCompilationUnits().length > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return "";
            }
            int start = offset;
            while (start > 0 && source.charAt(start - 1) != '\n') {
                start--;
            }
            int end = offset;
            while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                end++;
            }
            return source.substring(start, Math.min(end, start + 200)).trim();
        } catch (JavaModelException e) {
            return "";
        }
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }
            int offset = 0;
            int current = 0;
            while (current < line && offset < source.length()) {
                if (source.charAt(offset) == '\n') {
                    current++;
                }
                offset++;
            }
            return Math.min(offset + column, source.length());
        } catch (JavaModelException e) {
            return 0;
        }
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            int line = 0;
            for (int i = 0; source != null && i < offset && i < source.length(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        } catch (JavaModelException e) {
            return 0;
        }
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            int column = 0;
            for (int i = offset - 1; source != null && i >= 0; i--) {
                if (source.charAt(i) == '\n') {
                    break;
                }
                column++;
            }
            return column;
        } catch (JavaModelException e) {
            return 0;
        }
    }

    @Override
    public List<Path> getAllJavaFiles() {
        List<Path> files = new ArrayList<>();
        for (LoadedProject project : snapshot().projects) {
            try {
                for (IPackageFragmentRoot sourceRoot : project.javaProject().getPackageFragmentRoots()) {
                    if (sourceRoot.getKind() == IPackageFragmentRoot.K_SOURCE) {
                        collectFiles(sourceRoot, files);
                    }
                }
            } catch (JavaModelException e) {
                // a partial listing reads as a complete one at the call site, so it is never returned
                throw new SourceListingException(project.javaProject().getElementName(), e);
            }
        }
        return files;
    }

    private static void collectFiles(IPackageFragmentRoot sourceRoot, List<Path> files) throws JavaModelException {
        for (IJavaElement child : sourceRoot.getChildren()) {
            if (child instanceof IPackageFragment fragment) {
                for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                    IResource resource = unit.getResource();
                    IPath location = resource == null ? null : resource.getLocation();
                    if (location != null) {
                        files.add(Path.of(location.toOSString()));
                    }
                }
            }
        }
    }
}
