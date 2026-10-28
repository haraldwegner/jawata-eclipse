package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.jawata.eclipse.chat.ChatPage;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;

/**
 * Plan Stage 21, step 21.12 (contract §2.1, the chat to the engine): the chat bundle names no engine type. Of everything under
 * {@code org.jawata} it imports exactly one package, the small accessor that gives the engine's address as text; it requires no jawata
 * bundle. A chat that reached into {@code org.jawata.mcp} or {@code org.jawata.spi} would be part of the engine, not a client of it.
 */
public class ChatBundleManifestTest {

    /** The names a manifest header of the chat bundle lists, without their attributes and directives. */
    private static List<String> entriesOf(String header) {
        String value = FrameworkUtil.getBundle(ChatPage.class).getHeaders().get(header);
        List<String> entries = new ArrayList<>();
        if (value != null) {
            // split on the commas that are not inside a version range's quotes
            for (String entry : value.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                entries.add(entry.trim().split(";")[0].trim());
            }
        }
        return entries;
    }

    @Test
    public void ofTheEngineSideTheChatImportsOnlyTheAddressAccessor() {
        List<String> jawataImports = new ArrayList<>();
        for (String imported : entriesOf(Constants.IMPORT_PACKAGE)) {
            if (imported.startsWith("org.jawata")) {
                jawataImports.add(imported);
            }
        }
        assertEquals(List.of("org.jawata.eclipse.access"), jawataImports, "the packages under org.jawata the chat bundle imports");
    }

    @Test
    public void theChatRequiresNoJawataBundle() {
        List<String> jawataBundles = new ArrayList<>();
        for (String required : entriesOf(Constants.REQUIRE_BUNDLE)) {
            if (required.startsWith("org.jawata")) {
                jawataBundles.add(required);
            }
        }
        assertEquals(List.of(), jawataBundles, "the jawata bundles the chat bundle requires");
    }
}
