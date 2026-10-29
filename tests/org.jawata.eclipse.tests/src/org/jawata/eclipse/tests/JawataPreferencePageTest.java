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
    public void everySettingAPersonCanChangeIsOnThePageAndTheThreeLauncherOnesAreNot() {
        Opened opened = open();
        try {
            // the three the launcher sets or that are experiments (his ruling of 2026-10-28), written here on their own, apart from the page
            List<String> launcherOnes = List.of("jawata.dist.root", "jawata.workspace.root", "jawata.embed.symptoms");
            List<String> registry = SettingKeys.all().stream().map(SettingKey::name).toList();
            assertTrue(registry.containsAll(launcherOnes), "the engine still has the three, so their names here are right: " + registry);
            List<String> offered = registry.stream().filter(k -> !launcherOnes.contains(k)).toList();
            assertEquals(offered, opened.page().shownKeys(), "the page shows the engine's settings but those three, in the engine's order");
            assertTrue(offered.stream().allMatch(k -> opened.page().fieldFor(k) != null && !opened.page().fieldFor(k).isDisposed()),
                "and each of them has a live field to type into, not only a name in a list");
            assertTrue(launcherOnes.stream().allMatch(k -> opened.page().fieldFor(k) == null), "and the three have no field");
        } finally {
            onUserInterface(opened.shell()::dispose);
        }
    }

    /** A setting the page does not offer keeps its stored value when the page is saved: saving the page must not wipe what it never showed. */
    @Test
    public void savingThePageLeavesAStoredValueOfAHiddenSettingAlone() throws Exception {
        org.eclipse.core.runtime.preferences.IEclipsePreferences node = InstanceScope.INSTANCE.getNode(PLUGIN);
        node.put("jawata.embed.symptoms", "false");
        Opened opened = open();
        try {
            onUserInterface(() -> opened.page().performOk());
            assertEquals("false", node.get("jawata.embed.symptoms", null), "the stored value of a setting the page does not offer is still there");
        } finally {
            node.remove("jawata.embed.symptoms");
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
            java.nio.file.Files.createDirectories(picture.getParent());
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
    public void aLaunchPropertyIsNamedOnThePageBecauseItAppliesWhileTheFieldIsEmpty() {
        String key = SettingKeys.TIMEOUT_SECONDS.name();
        System.setProperty(key, "77");
        Opened opened = open();
        try {
            AtomicReference<String> shown = new AtomicReference<>();
            onUserInterface(() -> shown.set(opened.page().fieldFor(key).getToolTipText()));
            assertTrue(shown.get().contains("-D" + key + "=77"),
                "an empty field would otherwise read as the default while Eclipse was started with 77: " + shown.get());
        } finally {
            System.clearProperty(key);
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
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            String timeout = SettingKeys.TIMEOUT_SECONDS.name();
            // health_check reports the configuration it is running with, so a changed setting shows there
            com.fasterxml.jackson.databind.JsonNode before = json.readTree(McpClient.text(client.call("health_check", "{}")))
                .path("data").path("configuration");
            assertFalse(before.path("absolutePaths").asBoolean(true), "before the change absolute paths are off: " + before);
            assertNotEquals(45, before.path("timeoutSeconds").asInt(), "and the timeout is not 45 already: " + before);

            onUserInterface(() -> {
                opened.page().fieldFor(key).setText("true");
                opened.page().fieldFor(timeout).setText("45");
                opened.page().performOk();
            });
            assertEquals("true", InstanceScope.INSTANCE.getNode(PLUGIN).get(key, null), "the page stores the value as the plug-in's preference");
            assertEquals(Optional.of("true"), engine.engine().orElseThrow().host().settings().get(key), "and the engine's settings answer it");

            com.fasterxml.jackson.databind.JsonNode after = json.readTree(McpClient.text(client.call("health_check", "{}")))
                .path("data").path("configuration");
            assertTrue(after.path("absolutePaths").asBoolean(false), "a client sees absolute paths turned on in the next health_check: " + after);
            assertEquals(45, after.path("timeoutSeconds").asInt(), "and the timeout typed on the page: " + after);
        } finally {
            onUserInterface(() -> {
                opened.page().fieldFor(key).setText("");
                opened.page().fieldFor(SettingKeys.TIMEOUT_SECONDS.name()).setText("");
                opened.page().performOk();
                opened.shell().dispose();
            });
            WorkspaceProjects.deleteQuietly(project);
        }
        assertEquals(Optional.empty(), engine.engine().orElseThrow().host().settings().get(key), "an emptied field is a setting that is not set");
    }
}
