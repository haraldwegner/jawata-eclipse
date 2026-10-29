package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

/**
 * Pictures of the running workbench, taken for the ux seat and for the person
 * who wants to see what a view looks like. The picture is written to the folder
 * named by the property {@code jawata.ux.dir}, else to the temporary folder.
 */
public final class UxPicture {

	private UxPicture() {
	}

	/**
	 * Saves a picture of the workbench window, as the person sees it, for the ux
	 * seat.
	 */
	static void picture(String name) throws Exception {
		Path picture = Path.of(System.getProperty("jawata.ux.dir", System.getProperty("java.io.tmpdir")), name);
		Files.createDirectories(picture.getParent());
		UiThread.call(() -> {
			Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
			shell.setSize(1200, 800);
			shell.layout(true, true);
			Display display = shell.getDisplay();
			long until = System.currentTimeMillis() + 1500;
			while (System.currentTimeMillis() < until) {
				if (!display.readAndDispatch()) {
					display.sleep();
				}
			}
			Rectangle bounds = shell.getBounds();
			Image image = new Image(display, bounds.width, bounds.height);
			GC gc = new GC(display);
			gc.copyArea(image, bounds.x, bounds.y);
			gc.dispose();
			ImageLoader loader = new ImageLoader();
			loader.data = new ImageData[] { image.getImageData() };
			loader.save(picture.toString(), SWT.IMAGE_PNG);
			image.dispose();
			return null;
		});
		assertTrue(Files.size(picture) > 0, "a picture was saved at " + picture);
		System.out.println("jawata-ux picture " + picture);
	}
}
