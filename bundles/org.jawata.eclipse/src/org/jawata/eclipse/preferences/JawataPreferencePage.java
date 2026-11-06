package org.jawata.eclipse.preferences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.jawata.eclipse.host.EclipseSettings;
import org.jawata.mcp.engine.SettingKey;
import org.jawata.mcp.engine.SettingKeys;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Every setting the engine reads that a person can usefully change, on one of Eclipse's preference pages (plan Stage 17, spec D5; the
 * ones the engine does not mark for the person are not offered, see {@link #offeredToThePerson}). The page is built from the engine's own list of settings, {@link SettingKeys#all()}, so a
 * setting the engine gains is on the page without anyone remembering to add it. A field left empty is a setting that is not set, and the default shown
 * with it applies; what is typed here is stored as the plug-in's Eclipse preference of the same name, which is where the engine
 * reads it. There is no key or password anywhere in it, because the connection inside Eclipse has none.
 */
public final class JawataPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final String PLUGIN_ID = "org.jawata.eclipse";

    private final Map<String, Text> fields = new LinkedHashMap<>();

    @Override
    public void init(IWorkbench workbench) {
        setDescription("An empty field means the setting is not set here, and the default shown under it applies unless Eclipse was started "
            + "with that setting (the line under the field says so). Some settings are read only when Eclipse starts; restart Eclipse for those.");
    }

    @Override
    protected Control createContents(Composite parent) {
        ScrolledComposite scroll = new ScrolledComposite(parent, SWT.V_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite form = new Composite(scroll, SWT.NONE);
        form.setLayout(new GridLayout(2, false));
        IEclipsePreferences chosen = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
        IEclipsePreferences shared = ConfigurationScope.INSTANCE.getNode(PLUGIN_ID);
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(PLUGIN_ID);
        for (SettingKey key : offeredToThePerson(SettingKeys.all())) {
            boolean machineWide = EclipseSettings.MACHINE_WIDE.contains(key.name());
            String explanation = explanationOf(key, defaults.get(key.name(), key.fallback()), machineWide, System.getProperty(key.name()));
            Label name = new Label(form, SWT.NONE);
            name.setText(key.name());
            name.setToolTipText(explanation);
            Text field = new Text(form, SWT.BORDER);
            field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            // a machine-wide value is read from the installation's node; one stored per workspace before that is still shown until it is saved again
            field.setText(machineWide ? shared.get(key.name(), chosen.get(key.name(), "")) : chosen.get(key.name(), ""));
            field.setToolTipText(explanation);
            fields.put(key.name(), field);
            Label meaning = new Label(form, SWT.WRAP);
            GridData wide = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            wide.widthHint = 520;
            meaning.setLayoutData(wide);
            meaning.setText(explanation);
        }
        scroll.setContent(form);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        scroll.setMinSize(form.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return scroll;
    }

    /**
     * The settings meant for the person, in the engine's order: the engine says who each is for ({@link SettingKey#audience()}), so a setting
     * it gains is offered or kept off the page by its own word and not by a list of names kept here. The launcher's settings (the distribution
     * and workspace roots) and the experiment (symptom embedding) are not offered (his ruling of 2026-10-28); they keep working as before,
     * from the launch properties or a stored value, and a stored value is left alone when the page is saved.
     */
    public static List<SettingKey> offeredToThePerson(List<SettingKey> keys) {
        return keys.stream().filter(key -> key.audience() == SettingKey.Audience.PERSON).toList();
    }

    /**
     * What a setting is explained with on the page, in this host's words: the engine's meaning put together for a host that reads no
     * environment variable ({@link SettingKey#meaningFor(boolean)}), the default, and the two things only Eclipse knows.
     *
     * @param key         the setting
     * @param standing    the default that applies, as Eclipse's default preferences say it
     * @param machineWide whether the value is kept once for the installation
     * @param launched    the value Eclipse was started with for it (-D), or null
     */
    public static String explanationOf(SettingKey key, String standing, boolean machineWide, String launched) {
        String explanation = key.meaningFor(false) + " Default: " + standing + ".";
        if (machineWide) {
            explanation += " Kept once for this Eclipse installation: every workspace of it uses the value typed here.";
        }
        if (launched != null) {
            // what applies when this field is empty is not the default: Eclipse was started with a value for it
            explanation += " Eclipse was started with -D" + key.name() + "=" + launched + ", which applies until a value is typed here.";
        }
        return explanation;
    }

    /** The settings the page shows, by name, in the order they are shown. */
    public List<String> shownKeys() {
        return List.copyOf(fields.keySet());
    }

    /** The field a setting is typed into; the page must have been created. */
    public Text fieldFor(String key) {
        return fields.get(key);
    }

    @Override
    protected void performDefaults() {
        fields.values().forEach(field -> field.setText(""));
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        IEclipsePreferences chosen = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
        IEclipsePreferences shared = ConfigurationScope.INSTANCE.getNode(PLUGIN_ID);
        fields.forEach((key, field) -> {
            String value = field.getText().trim();
            IEclipsePreferences home = EclipseSettings.MACHINE_WIDE.contains(key) ? shared : chosen;
            if (home != chosen) {
                // a machine-wide value lives in one place only: a copy stored per workspace by an earlier version is dropped
                chosen.remove(key);
            }
            if (value.isEmpty()) {
                home.remove(key);
            } else {
                home.put(key, value);
            }
        });
        try {
            chosen.flush();
            shared.flush();
        } catch (BackingStoreException e) {
            setErrorMessage("The settings could not be saved: " + e.getMessage());
            return false;
        }
        return true;
    }
}
