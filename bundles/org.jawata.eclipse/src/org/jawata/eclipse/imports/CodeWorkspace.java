package org.jawata.eclipse.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The folders a VS Code workspace file names (plan Stage 22, spec D8). A {@code .code-workspace} file lists {@code folders[].path};
 * a relative path is relative to the folder the file is in, an absolute one stands as it is. Nothing else in the file matters here, and
 * this class creates no project and writes nothing: it only says which folders to hand to Eclipse's import.
 */
public final class CodeWorkspace {

    private CodeWorkspace() {
    }

    /**
     * Reads the folders of a workspace file, in the order the file lists them.
     *
     * @param file a {@code .code-workspace} file
     * @return the folders, absolute and normalised; an entry without a {@code path} is skipped
     * @throws IOException if the file cannot be read or is not JSON
     */
    public static List<Path> folders(Path file) throws IOException {
        Path base = file.toAbsolutePath().getParent();
        JsonNode folders = new ObjectMapper().readTree(Files.readString(file)).path("folders");
        List<Path> found = new ArrayList<>();
        for (JsonNode folder : folders) {
            JsonNode path = folder.path("path");
            if (path.isTextual()) {
                found.add(base.resolve(path.asText()).normalize());
            }
        }
        return found;
    }
}
