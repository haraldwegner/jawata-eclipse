package org.jawata.eclipse.commit;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.framework.FrameworkUtil;

/**
 * Judges a commit message draft before the person is shown it (plan Stage 22b, spec D13): a draft carrying AI attribution is refused with its
 * reason. The cases come from the one list the repository's commit-msg hook reads as well
 * ({@code resources/commit-attribution-patterns.txt}), so the hook and this check cannot drift apart. Each case is applied to one line at a
 * time, as {@code grep} applies it.
 */
public final class CommitDraftCheck {

    private static final String LIST = "resources/commit-attribution-patterns.txt";

    private final List<Pattern> refused;

    private CommitDraftCheck(List<Pattern> refused) {
        this.refused = List.copyOf(refused);
    }

    /**
     * The check the plug-in uses: the list inside this bundle.
     *
     * @return the check
     * @throws IOException when the list cannot be read
     */
    public static CommitDraftCheck fromBundle() throws IOException {
        URL entry = FrameworkUtil.getBundle(CommitDraftCheck.class).getEntry(LIST);
        if (entry == null) {
            throw new IOException("the list of refused commit-message cases is missing from the plug-in: " + LIST);
        }
        try (InputStream in = entry.openStream()) {
            return fromList(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * A check over the text of such a list: one case per line, lines starting with # and blank lines ignored.
     *
     * @param list the text of the list
     * @return the check
     */
    public static CommitDraftCheck fromList(String list) {
        List<Pattern> patterns = new ArrayList<>();
        for (String line : list.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                patterns.add(Pattern.compile(line.strip().replace("[[:space:]]", "\\s"), Pattern.CASE_INSENSITIVE));
            }
        }
        return new CommitDraftCheck(patterns);
    }

    /**
     * Why a draft is refused.
     *
     * @param draft the text of the draft
     * @return the reason in words the person can act on, or empty when the draft is accepted
     */
    public Optional<String> refusal(String draft) {
        for (String line : draft.split("\\R")) {
            for (Pattern pattern : refused) {
                Matcher found = pattern.matcher(line);
                if (found.find()) {
                    return Optional.of("The draft carries AI attribution (\"" + found.group().strip() + "\"), which commit messages here may not. Remove it and ask again.");
                }
            }
        }
        return Optional.empty();
    }
}
