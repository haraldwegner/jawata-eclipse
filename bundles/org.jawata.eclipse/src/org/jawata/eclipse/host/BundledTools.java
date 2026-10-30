package org.jawata.eclipse.host;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Optional;

import org.osgi.framework.Bundle;

/**
 * The runner tools of the released engine (the test runner, JUnit's console launcher, JaCoCo's agent, PIT) as files on disk (Stage 26,
 * found by the dogfood of 2026-10-30). The engine starts a forked JVM from these jars for {@code run_tests}, coverage and mutation, and a
 * forked JVM needs real files; the plug-in carries them in its {@code tools/} folder, taken from the engine release by
 * {@code scripts/fetch-engine.sh}, and this puts them where the engine's setting {@code jawata.tools.dir} can name.
 */
final class BundledTools {

    /** The folder of the plug-in that holds the jars. */
    static final String ENTRY = "tools";

    private BundledTools() {
    }

    /**
     * Writes each jar of the bundle's {@code tools/} folder into {@code target}, replacing a file whose bytes differ and leaving one that
     * is the same untouched, so a plug-in update puts the new jars in place and a restart costs nothing.
     *
     * @param bundle the plug-in carrying the folder
     * @param target the folder on disk to put the jars in
     * @return the folder, or empty when this build of the plug-in carries none
     * @throws IOException when a jar cannot be written
     */
    static Optional<Path> installedAt(Bundle bundle, Path target) throws IOException {
        Enumeration<URL> entries = bundle.findEntries(ENTRY, "*.jar", false);
        if (entries == null || !entries.hasMoreElements()) {
            return Optional.empty();
        }
        Files.createDirectories(target);
        while (entries.hasMoreElements()) {
            URL entry = entries.nextElement();
            String path = entry.getPath();
            Path file = target.resolve(path.substring(path.lastIndexOf('/') + 1));
            byte[] bytes;
            try (InputStream in = entry.openStream()) {
                bytes = in.readAllBytes();
            }
            if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
                Path partial = target.resolve(file.getFileName() + ".part");
                Files.write(partial, bytes);
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return Optional.of(target);
    }
}
