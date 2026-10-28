package org.jawata.eclipse.run;

import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.ui.actions.CompoundContributionItem;

/**
 * The entries of Run › Run for jawata (plan Stage 19, path B; contract §6.1): one per run configuration of the person's that can be
 * started, each starting it for jawata in Run mode so the agent can attach while the person reproduces a problem. The list is read
 * when the menu opens, so a configuration made a moment ago is there.
 */
public final class RunForJawataMenu extends CompoundContributionItem {

    /** What the submenu shows when the person has no run configuration to start. */
    static final String NO_CONFIGURATION = "No run configuration yet (make one under Run > Run Configurations)";

    private final HostRuns runs;

    /** The menu over this plug-in's own runs, as Eclipse creates it from {@code plugin.xml}. */
    public RunForJawataMenu() {
        this(HostRuns.shared());
    }

    /** @param runs where a chosen configuration is started */
    RunForJawataMenu(HostRuns runs) {
        this.runs = runs;
    }

    @Override
    protected IContributionItem[] getContributionItems() {
        List<String> names = runs.configurations();
        if (names.isEmpty()) {
            // an empty submenu would leave the person guessing; say what is missing and where it is made (menu items show no mouseover)
            Action none = new Action(NO_CONFIGURATION) {
                @Override
                public void run() {
                    // a disabled entry that only explains
                }
            };
            none.setEnabled(false);
            return new IContributionItem[] { new ActionContributionItem(none) };
        }
        IContributionItem[] items = new IContributionItem[names.size()];
        for (int i = 0; i < items.length; i++) {
            String name = names.get(i);
            Action action = new Action(name) {
                @Override
                public void run() {
                    runs.run(name, HostRuns.PERSON);
                }
            };
            action.setToolTipText("Run " + name + " in Run mode with the debug agent, so the agent can attach while you reproduce the problem. "
                + "Your configuration is not changed.");
            items[i] = new ActionContributionItem(action);
        }
        return items;
    }
}
