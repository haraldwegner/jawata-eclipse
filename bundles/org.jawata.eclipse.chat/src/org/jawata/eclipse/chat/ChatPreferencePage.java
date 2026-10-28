package org.jawata.eclipse.chat;

import java.util.Optional;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Where the person sets the chat up (plan Stage 21, step 21.8): which agent to talk to and the folder it works in. Two settings, each
 * with what it does and what leaving it empty means; a change reaches an open chat at once, without restarting it.
 */
public final class ChatPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    /** The id plugin.xml gives the page, which opens it. */
    public static final String ID = "org.jawata.eclipse.chat.preferences";

    private static final String AGENT_EXAMPLE = "{\"command\": \"npx\", \"args\": [\"-y\", \"@agentclientprotocol/claude-agent-acp@0.93.0\"]}";

    private Text agent;
    private Text folder;

    @Override
    public void init(IWorkbench workbench) {
        setDescription("The chat talks to one agent: a program that speaks the Agent Client Protocol. Changes reach an open chat when you apply them.");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite page = new Composite(parent, SWT.NONE);
        page.setLayout(new GridLayout(2, false));
        Preferences node = InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);

        label(page, "Agent", "The program the chat starts and talks to.", 2);
        agent = new Text(page, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        agent.setText(node.get(PreferencesChatSettings.AGENT, ""));
        agent.setMessage(AGENT_EXAMPLE);
        agent.setToolTipText("One JSON object: \"command\" is the program, \"args\" its arguments, \"env\" extra environment variables. Example: " + AGENT_EXAMPLE);
        GridData agentLayout = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        agentLayout.heightHint = 90;
        agent.setLayoutData(agentLayout);
        label(page, "Leave it empty to start no agent: the chat then tells you to come here. The program is started on this machine with the folder below as its working folder.", "", 2);

        label(page, "Working folder", "The folder the agent works in.", 2);
        folder = new Text(page, SWT.BORDER | SWT.SINGLE);
        folder.setText(node.get(PreferencesChatSettings.WORKING_FOLDER, ""));
        folder.setToolTipText("The agent reads and changes files below this folder. Empty means the folder of the Eclipse workspace.");
        folder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button browse = new Button(page, SWT.PUSH);
        browse.setText("Browse...");
        browse.setToolTipText("Pick the folder in a dialog.");
        browse.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                DirectoryDialog dialog = new DirectoryDialog(getShell());
                dialog.setFilterPath(folder.getText());
                String picked = dialog.open();
                if (picked != null) {
                    folder.setText(picked);
                }
            }
        });
        label(page, "Empty means the folder of the Eclipse workspace.", "", 2);
        return page;
    }

    private static Label label(Composite parent, String text, String tooltip, int span) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(text);
        if (!tooltip.isEmpty()) {
            label.setToolTipText(tooltip);
        }
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1));
        return label;
    }

    @Override
    protected void performDefaults() {
        agent.setText("");
        folder.setText("");
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        Optional<String> problem = PreferencesChatSettings.problemWith(agent.getText());
        if (problem.isPresent()) {
            setErrorMessage("The agent: " + problem.get());
            return false;
        }
        setErrorMessage(null);
        Preferences node = InstanceScope.INSTANCE.getNode(PreferencesChatSettings.NODE);
        node.put(PreferencesChatSettings.AGENT, agent.getText().trim());
        node.put(PreferencesChatSettings.WORKING_FOLDER, folder.getText().trim());
        try {
            node.flush();
        } catch (BackingStoreException unsaved) {
            setErrorMessage("The settings could not be saved: " + unsaved.getMessage());
            return false;
        }
        return true;
    }
}
