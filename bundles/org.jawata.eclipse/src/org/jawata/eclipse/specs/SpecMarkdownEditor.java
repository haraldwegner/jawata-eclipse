package org.jawata.eclipse.specs;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.mylyn.internal.wikitext.ui.editor.MarkupEditor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;

/**
 * Spec D6 (his option B): the default editor for Markdown files — WikiText's own editor (source, preview, outline, highlighting)
 * reading the file with {@link GfmTablesMarkdownLanguage}, so the preview shows the specs' tables.
 *
 * <p>Why a subclass of WikiText's internal editor: WikiText maps a file extension to the FIRST language registered for it and logs
 * an error for any other, so a second Markdown language cannot be registered for {@code .md}; and the editor's other route, a
 * per-file language property, would write into the person's workspace. A language the person chose for a file themselves (the
 * editor's "Markup Language" menu) is honoured. The in-download end-to-end test is what notices a WikiText release changing this
 * class.
 */
@SuppressWarnings("restriction")
public class SpecMarkdownEditor extends MarkupEditor {

    /** This editor's id, as {@code plugin.xml} declares it. */
    public static final String ID = "org.jawata.eclipse.specs.markdownEditor";

    @Override
    protected void doSetInput(IEditorInput input) throws CoreException {
        super.doSetInput(input);
        IFile file = input instanceof IFileEditorInput fileInput ? fileInput.getFile() : null;
        if (file == null || MarkupEditor.getMarkupLanguagePreference(file) == null) {
            setMarkupLanguage(new GfmTablesMarkdownLanguage(), false);   // false: nothing is stored on the file
        }
    }
}
