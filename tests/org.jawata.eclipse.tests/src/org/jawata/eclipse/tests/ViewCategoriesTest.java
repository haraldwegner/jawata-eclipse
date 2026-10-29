package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.views.IViewCategory;
import org.junit.jupiter.api.Test;

/**
 * The person opens Window > Show View and finds the jawata views together: the chat, the terminal and the commit draft are listed in one
 * group named jawata and not in three groups of the same name (found by the ux look at the commit draft view, plan Stage 22b).
 */
public class ViewCategoriesTest {

    @Test
    public void theJawataViewsAreListedInOneGroup() {
        List<IViewCategory> groups = UiThread.call(() -> Arrays.stream(PlatformUI.getWorkbench().getViewRegistry().getCategories())
            .filter(category -> "jawata".equals(category.getLabel())).toList());
        assertEquals(1, groups.size(), "groups named jawata in Show View: " + groups.stream().map(IViewCategory::getId).toList());
        Set<String> views = UiThread.call(() -> Arrays.stream(groups.get(0).getViews()).map(view -> view.getId()).collect(Collectors.toSet()));
        for (String expected : new String[] { "org.jawata.eclipse.chat.view", "org.jawata.eclipse.terminal.view", "org.jawata.eclipse.commit.view" }) {
            assertTrue(views.contains(expected), expected + " is in the jawata group: " + views);
        }
    }

    /**
     * The person choosing in Window > Show View reads a sentence before opening a view (ledger 28k-A13c-7 a): without one the jawata terminal
     * and Eclipse's own Terminal are listed side by side with nothing to tell them apart.
     */
    @Test
    public void everyJawataViewSaysInASentenceWhatItIsFor() {
        for (String id : new String[] { "org.jawata.eclipse.chat.view", "org.jawata.eclipse.terminal.view", "org.jawata.eclipse.commit.view" }) {
            String description = UiThread.call(() -> PlatformUI.getWorkbench().getViewRegistry().find(id).getDescription());
            assertTrue(description != null && description.trim().length() > 30, id + " has a description in Show View: '" + description + "'");
        }
        String terminal = UiThread.call(() -> PlatformUI.getWorkbench().getViewRegistry().find("org.jawata.eclipse.terminal.view").getDescription());
        assertTrue(terminal.contains("Eclipse's own Terminal"), "the terminal's description says how it differs from Eclipse's Terminal: '" + terminal + "'");
    }
}
