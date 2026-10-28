package org.jawata.eclipse.chat;

import java.util.List;

import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.ui.IWorkbenchPartSite;

/**
 * Moves a view into the editor area, so the person sees it as a tab among their files (plan Stage 23, his pick of 2026-10-28). The view stays
 * a view: Eclipse still lists it under Window > Show View, finds it by its id and restores it after a restart. It is done with the workbench's
 * public model services, and a workbench whose model does not look as expected leaves the view where it is.
 */
final class EditorAreaMove {

    private static final String SHARED_EDITOR_AREA = "org.eclipse.ui.editorss";

    private EditorAreaMove() {
    }

    /**
     * @param site the site of the view to move
     * @return true when the view now sits in the editor area; false when it was left where it was
     */
    static boolean moveInto(IWorkbenchPartSite site) {
        MPart part = site.getService(MPart.class);
        EModelService models = site.getService(EModelService.class);
        if (part == null || models == null || part.getCurSharedRef() == null) {
            return false;
        }
        // the part itself has no parent in the model; the placeholder that stands for it in the perspective is what sits in a stack
        MUIElement shown = part.getCurSharedRef();
        MWindow window = models.getTopLevelWindowFor(shown);
        // the id names the placeholder the perspective holds; the area is what it refers to
        if (window == null || !(models.find(SHARED_EDITOR_AREA, window) instanceof MPlaceholder placeholder) || !(placeholder.getRef() instanceof MArea area)) {
            return false;
        }
        List<MPartStack> stacks = models.findElements(area, null, MPartStack.class);
        if (stacks.isEmpty() || !(shown instanceof MStackElement element)) {
            return false;
        }
        MPartStack target = stacks.get(0);
        if (target.getChildren().contains(element)) {
            return true;
        }
        target.getChildren().add(element);
        target.setSelectedElement(element);
        return true;
    }

    /**
     * Eclipse opens a view where it was last, so a chat that was in the middle comes back there; this puts it in the first panel of views
     * the perspective has, for a person who chose the side panel.
     *
     * @param site the site of the view to move
     * @return true when the view was in the editor area and now sits in a panel of views; false when it was left where it was
     */
    static boolean moveOut(IWorkbenchPartSite site) {
        MPart part = site.getService(MPart.class);
        EModelService models = site.getService(EModelService.class);
        if (part == null || models == null || !(part.getCurSharedRef() instanceof MStackElement element) || !inEditorArea(element)) {
            return false;
        }
        MWindow window = models.getTopLevelWindowFor(element);
        MPerspective perspective = window == null ? null : models.getActivePerspective(window);
        if (perspective == null) {
            return false;
        }
        for (MPartStack stack : models.findElements(perspective, null, MPartStack.class)) {
            if (!inEditorArea(stack) && stack.getChildren().stream().anyMatch(EditorAreaMove::holdsAView)) {
                stack.getChildren().add(element);
                stack.setSelectedElement(element);
                return true;
            }
        }
        return false;
    }

    private static boolean inEditorArea(MUIElement element) {
        MUIElement up = element;
        while (up != null && !(up instanceof MArea)) {
            up = up.getParent();
        }
        return up != null;
    }

    private static boolean holdsAView(MStackElement child) {
        MUIElement shown = child instanceof MPlaceholder placeholder ? placeholder.getRef() : child;
        return shown != null && shown.getTags().contains("View");
    }
}
