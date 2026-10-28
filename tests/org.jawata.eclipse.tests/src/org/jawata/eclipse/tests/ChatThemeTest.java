package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jawata.eclipse.chat.ChatTheme;
import org.jawata.eclipse.chat.ChatTheme.Value;
import org.junit.jupiter.api.Test;

/**
 * Plan Stage 21, step 21.10 (ledger 28i-C19-6): the chat view reads neutral theme inputs ({@code --jc-host-*}) and names no Eclipse value;
 * the Eclipse map says which value each input is. Both ways, as the VS Code extension's test holds its own map: an input the built view
 * reads has a mapping, a mapping is an input the view reads, and each input is the value of its meaning, written down here once more so
 * that a changed mapping is a decision and not a slip.
 */
public class ChatThemeTest {

    /** Which Eclipse value each input is, written down once more on purpose. */
    private static final Map<String, Value> EXPECTED = new TreeMap<>(Map.ofEntries(
        Map.entry("font", Value.DEFAULT_FONT), Map.entry("size", Value.FONT_SIZE), Map.entry("mono", Value.TEXT_FONT),
        Map.entry("text", Value.FOREGROUND), Map.entry("ground", Value.BACKGROUND), Map.entry("raised", Value.LIST_BACKGROUND),
        Map.entry("edge", Value.BORDER), Map.entry("field", Value.FIELD_BACKGROUND), Map.entry("field-text", Value.FIELD_FOREGROUND),
        Map.entry("accent", Value.SELECTION_BACKGROUND), Map.entry("on-accent", Value.SELECTION_FOREGROUND),
        Map.entry("mine", Value.MINE_BACKGROUND), Map.entry("mine-text", Value.MINE_FOREGROUND),
        Map.entry("ok", Value.SUCCESS), Map.entry("bad", Value.ERROR), Map.entry("warn", Value.WARNING), Map.entry("link", Value.LINK)));

    /** The built page, found from the folder the tests run in up to the repository. */
    private static String builtView() throws IOException {
        for (Path folder = Path.of("").toAbsolutePath(); folder != null; folder = folder.getParent()) {
            Path script = folder.resolve("bundles/org.jawata.eclipse.chat/page/chat.js");
            if (Files.isRegularFile(script)) {
                return Files.readString(script, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no bundles/org.jawata.eclipse.chat/page/chat.js above " + Path.of("").toAbsolutePath() + ": run scripts/build-chat-page.sh");
    }

    @Test
    public void everyInputTheBuiltViewReadsIsSetAndEveryInputSetIsRead() throws IOException {
        TreeSet<String> read = new TreeSet<>();
        Matcher matcher = Pattern.compile("--jc-host-([a-z-]+)").matcher(builtView());
        while (matcher.find()) {
            read.add(matcher.group(1));
        }
        assertTrue(read.size() > 10, "the view reads its theme inputs: " + read);
        assertEquals(read, new TreeSet<>(ChatTheme.INPUTS.keySet()), "the inputs the view reads and the inputs Eclipse sets");
    }

    @Test
    public void theBuiltViewNamesNoEclipseValue() throws IOException {
        String view = builtView();
        assertFalse(view.contains("org.eclipse."), "the built view names an Eclipse class or id");
        assertFalse(view.contains("--eclipse-"), "the built view names an Eclipse theme variable");
    }

    @Test
    public void eachInputIsTheEclipseValueOfItsMeaning() {
        assertEquals(EXPECTED, new TreeMap<>(ChatTheme.INPUTS));
    }

    @Test
    public void theStylesheetSetsEachInputFromTheValueOfItsMeaning() {
        String css = ChatTheme.css(value -> "<" + value.name() + ">");
        for (Map.Entry<String, Value> input : EXPECTED.entrySet()) {
            assertTrue(css.contains("--jc-host-" + input.getKey() + ": <" + input.getValue().name() + ">;"), input.getKey() + " in " + css);
        }
        assertEquals(EXPECTED.size(), css.split("--jc-host-").length - 1, "one declaration per input: " + css);
    }
}
