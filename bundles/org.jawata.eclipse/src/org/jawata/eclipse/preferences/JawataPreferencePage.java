package org.jawata.eclipse.preferences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.jawata.mcp.engine.SettingKey;
import org.jawata.mcp.engine.SettingKeys;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Every setting the engine reads, on one of Eclipse's preference pages (plan Stage 17, spec D5: "every setting is on Eclipse's
 * preference pages"). The page is built from the engine's own list of settings, {@link SettingKeys#all()}, so a setting the engine
 * gains is on the page without anyone remembering to add it. A field left empty is a setting that is not set, and the default shown
 * with it applies; what is typed here is stored as the plug-in's Eclipse preference of the same name, which is where the engine
 * reads it. There is no key or password anywhere in it, because the connection inside Eclipse has none.
 */
public final class JawataPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final String PLUGIN_ID = "org.jawata.eclipse";

    private final Map<String, Text> fields = new LinkedHashMap<>();

    @Override
    public void init(IWorkbench workbench) {
        setDescription("An empty field means the setting is not set, and the default shown under it applies. Some settings are read "
            + "only when Eclipse starts; restart Eclipse for those.");
    }

    @Override
    protected Control createContents(Composite parent) {
        ScrolledComposite scroll = new ScrolledComposite(parent, SWT.V_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite form = new Composite(scroll, SWT.NONE);
        form.setLayout(new GridLayout(2, false));
        IEclipsePreferences chosen = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(PLUGIN_ID);
        for (SettingKey key : SettingKeys.all()) {
            String standing = defaults.get(key.name(), key.fallback());
            String explanation = key.meaning() + " Default: " + standing + ".";
            String launched = System.getProperty(key.name());
            if (launched != null) {
                // what applies when this field is empty is not the default: Eclipse was started with a value for it
                explanation += " Eclipse was started with -D" + key.name() + "=" + launched + ", which applies until a value is typed here.";
            }
            Label name = new Label(form, SWT.NONE);
            name.setText(key.name());
            name.setToolTipText(explanation);
            Text field = new Text(form, SWT.BORDER);
            field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            field.setText(chosen.get(key.name(), ""));
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
        fields.forEach((key, field) -> {
            String value = field.getText().trim();
            if (value.isEmpty()) {
                chosen.remove(key);
            } else {
                chosen.put(key, value);
            }
        });
        try {
            chosen.flush();
        } catch (BackingStoreException e) {
            setErrorMessage("The settings could not be saved: " + e.getMessage());
            return false;
        }
        return true;
    }
}
