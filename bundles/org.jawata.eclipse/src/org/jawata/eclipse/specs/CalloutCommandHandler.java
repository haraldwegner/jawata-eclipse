package org.jawata.eclipse.specs;

import java.util.Optional;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Spec D14: the five commands the person has on a Markdown file's comments and suggested changes. "Comment" and "Suggest change" write
 * a mark at the selection; "Accept", "Reject" and "Resolve" act on the mark the caret is in. Each one edits the open document and nothing
 * else, as a single undoable change; the person saves the file as they would any edit. Nothing here runs a command by itself: they run
 * from the editor's menu or a key the person presses.
 */
public final class CalloutCommandHandler extends AbstractHandler {

    /** The command that writes a comment after the selected text. */
    public static final String COMMENT = "org.jawata.eclipse.specs.comment";
    /** The command that marks the selected text removed and opens an addition after it. */
    public static final String SUGGEST_CHANGE = "org.jawata.eclipse.specs.suggestChange";
    /** The command that accepts the mark at the caret. */
    public static final String ACCEPT = "org.jawata.eclipse.specs.accept";
    /** The command that rejects the mark at the caret. */
    public static final String REJECT = "org.jawata.eclipse.specs.reject";
    /** The command that resolves the comment at the caret. */
    public static final String RESOLVE = "org.jawata.eclipse.specs.resolve";
    /** The optional parameter of {@link #COMMENT} and {@link #SUGGEST_CHANGE}: the text to write; without it the person is asked. */
    public static final String TEXT_PARAMETER = "org.jawata.eclipse.specs.text";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart part = HandlerUtil.getActiveEditorChecked(event);
        if (!(part instanceof ITextEditor editor) || !(editor.getSelectionProvider().getSelection() instanceof ITextSelection selection)) {
            return null;
        }
        IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (document == null) {
            return null;
        }
        String text = document.get();
        Optional<CriticMarkup.Edit> edit = switch (event.getCommand().getId()) {
            case COMMENT -> asked(event, "Comment", "What is your comment on the selected text?")
                .map(comment -> CriticMarkup.comment(selection.getOffset() + selection.getLength(), comment));
            case SUGGEST_CHANGE -> selection.getLength() == 0 ? Optional.empty()
                : asked(event, "Suggest a change", "What should the selected text become? (leave empty to type it into the mark)")
                    .map(addition -> CriticMarkup.suggestChange(text, selection.getOffset(), selection.getOffset() + selection.getLength(), addition));
            case ACCEPT -> CriticMarkup.markAt(text, selection.getOffset()).map(CriticMarkup::accept);
            case REJECT -> CriticMarkup.markAt(text, selection.getOffset()).map(CriticMarkup::reject);
            case RESOLVE -> CriticMarkup.markAt(text, selection.getOffset()).flatMap(CriticMarkup::resolve);
            default -> Optional.empty();
        };
        if (edit.isPresent()) {
            try {
                document.replace(edit.get().offset(), edit.get().length(), edit.get().replacement());
            } catch (BadLocationException stale) {
                throw new ExecutionException("the document changed under the command", stale);
            }
        }
        return null;
    }

    /** The text a command writes: its parameter when it was run with one, else what the person types into a dialog; empty when they cancel. */
    private static Optional<String> asked(ExecutionEvent event, String title, String message) {
        String given = event.getParameter(TEXT_PARAMETER);
        if (given != null) {
            return Optional.of(given);
        }
        InputDialog dialog = new InputDialog(HandlerUtil.getActiveShell(event), title, message, "", null);
        return dialog.open() == Window.OK ? Optional.of(dialog.getValue()) : Optional.empty();
    }
}
