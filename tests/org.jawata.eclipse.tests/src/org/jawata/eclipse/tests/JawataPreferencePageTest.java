package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.eclipse.preferences.JawataPreferencePage;
import org.jawata.mcp.engine.SettingKey;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 17, spec D5: "every setting is on Eclipse's preference pages", and a value typed there is what the engine reads. The
 * page is opened in the running workbench, as the preferences dialog opens it, and compared with the engine's own list of settings;
 * a value typed into it and saved reaches a tool answer a client can read.
 */
public class JawataPreferencePageTest {

    private static final String PLUGIN = "org.jawata.eclipse";

    /** A page created on the user-interface thread, with the shell it sits in, which the caller disposes. */
    private record Opened(JawataPreferencePage page, Shell shell) {
    }

    private static Opened open() {
        Display display = PlatformUI.getWorkbench().getDisplay();
        AtomicReference<Opened> opened = new AtomicReference<>();
        display.syncExec(() -> {
            JawataPreferencePage page = new JawataPreferencePage();
            page.init(PlatformUI.getWorkbench());
            Shell shell = new Shell(display);
            page.createControl(shell);
            opened.set(new Opened(page, shell));
        });
        return opened.get();
    }

    private static void onUserInterface(Runnable work) {
        PlatformUI.getWorkbench().getDisplay().syncExec(work);
    }

    @Test
    public void everySettingTheEngineReadsIsOnThePage() {
        Opened opened = open();
        try {
            List<String> registry = SettingKeys.all().stream().map(SettingKey::name).toList();
            assertEquals(registry, opened.page().shownKeys(), "the page shows the engine's settings, all of them and in its order");
            assertFalse(opened.page().shownKeys().stream().anyMatch(k -> k.toLowerCase().contains("password") || k.toLowerCase().endsWith(".key")),
                "and none is a key or password: the connection inside Eclipse has none");
        } finally {
            onUserInterface(opened.shell()::dispose);
        }
    }

    /** The first scrolled area inside a control, which is where the page's form lives. */
    private static org.eclipse.swt.custom.ScrolledComposite scrolledAreaIn(org.eclipse.swt.widgets.Control control) {
        if (control instanceof org.eclipse.swt.custom.ScrolledComposite scrolled) {
            return scrolled;
        }
        if (control instanceof org.eclipse.swt.widgets.Composite composite) {
            for (org.eclipse.swt.widgets.Control child : composite.getChildren()) {
                org.eclipse.swt.custom.ScrolledComposite found = scrolledAreaIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void thePageIsReachableByScrollingAndIsRendered() throws Exception {
        Opened opened = open();
        try {
            java.nio.file.Path picture = java.nio.file.Path.of(System.getProperty("jawata.ux.dir", System.getProperty("java.io.tmpdir")),
                "preference-page.png");
            AtomicReference<Boolean> reachable = new AtomicReference<>(false);
            onUserInterface(() -> {
                opened.shell().setLayout(new org.eclipse.swt.layout.FillLayout());
                opened.shell().setSize(640, 480);
                opened.shell().open();
                opened.shell().layout(true, true);
                org.eclipse.swt.custom.ScrolledComposite scroll = scrolledAreaIn(opened.page().getControl());
                reachable.set(scroll != null && scroll.getVerticalBar() != null && scroll.getVerticalBar().isVisible()
                    && scroll.getVerticalBar().getMaximum() > scroll.getVerticalBar().getThumb());
                org.eclipse.swt.widgets.Display display = opened.shell().getDisplay();
                while (display.readAndDispatch()) {
                    // let the page paint before it is copied
                }
                org.eclipse.swt.graphics.Image image = new org.eclipse.swt.graphics.Image(display, opened.shell().getBounds());
                org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(display);
                gc.copyArea(image, opened.shell().getBounds().x, opened.shell().getBounds().y);
                gc.dispose();
                org.eclipse.swt.graphics.ImageLoader loader = new org.eclipse.swt.graphics.ImageLoader();
                loader.data = new org.eclipse.swt.graphics.ImageData[] { image.getImageData() };
                loader.save(picture.toString(), org.eclipse.swt.SWT.IMAGE_PNG);
                image.dispose();
            });
            assertTrue(reachable.get(), "the form is taller than the page and scrolls, so every setting can be reached");
            assertTrue(java.nio.file.Files.size(picture) > 0, "and a picture of the page was saved at " + picture);
            System.out.println("jawata-ux picture " + picture);
        } finally {
            onUserInterface(opened.shell()::dispose);
        }
    }

    @Test
    public void aValueTypedOnThePageIsWhatTheEngineReadsAndShowsInAToolAnswer() throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        McpClient client = McpClient.connect(at.url());
        String key = SettingKeys.ABSOLUTE_PATHS.name();
        IProject project = WorkspaceProjects.java("jawata-prefs-");
        Opened opened = open();
        try {
            Await.found("the engine sees the project", () -> {
                try {
                    return McpClient.text(client.call("search_symbols", "{\"query\":\"Greeter\"}")).contains("qualifiedName")
                        ? Optional.of(true) : Optional.empty();
                } catch (Exception e) {
                    return Optional.empty();
                }
            });
            String location = project.getLocation().toOSString();
            String relative = McpClient.text(client.call("health_check", "{}"));

            onUserInterface(() -> {
                opened.page().fieldFor(key).setText("true");
                opened.page().performOk();
            });
            assertEquals("true", InstanceScope.INSTANCE.getNode(PLUGIN).get(key, null), "the page stores the value as the plug-in's preference");
            assertEquals(Optional.of("true"), engine.engine().orElseThrow().host().settings().get(key), "and the engine's settings answer it");

            String absolute = McpClient.text(client.call("health_check", "{}"));
            assertNotEquals(relative, absolute, "a client sees the change in the next health_check answer");
            assertTrue(absolute.contains(location), "paths are absolute now: the project's folder " + location + " is in the answer");
        } finally {
            onUserInterface(() -> {
                opened.page().fieldFor(key).setText("");
                opened.page().performOk();
                opened.shell().dispose();
            });
            WorkspaceProjects.deleteQuietly(project);
        }
        assertEquals(Optional.empty(), engine.engine().orElseThrow().host().settings().get(key), "an emptied field is a setting that is not set");
    }
}
