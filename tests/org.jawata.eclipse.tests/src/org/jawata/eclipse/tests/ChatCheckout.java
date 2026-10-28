package org.jawata.eclipse.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The sibling checkout of jawata-chat that the tests take recorded sessions, the replay agent and the goldens from. */
final class ChatCheckout {

    private ChatCheckout() {
    }

    /** The checkout: the folder JAWATA_CHAT names, else the jawata-chat next to this repository. */
    static Path root() {
        String named = System.getenv("JAWATA_CHAT");
        if (named != null && !named.isBlank()) {
            return Path.of(named);
        }
        for (Path folder = Path.of("").toAbsolutePath(); folder != null; folder = folder.getParent()) {
            Path sibling = folder.resolveSibling("jawata-chat");
            if (Files.isDirectory(sibling)) {
                return sibling;
            }
        }
        throw new AssertionError("jawata-chat is not next to jawata-eclipse and JAWATA_CHAT is not set");
    }

    /** A recorded session of the contract package, such as R1. */
    static Path session(String name) {
        return root().resolve("packages/acp-contract/sessions/" + name + ".jsonl");
    }

    /** The command that starts the replay agent on a recorded session. */
    static List<String> replayCommand(String sessionName) {
        return List.of("node", root().resolve("packages/acp-contract/dist/bin/replay.js").toString(), session(sessionName).toString());
    }

    /** The view stream chat-core records for a session, which every host's chat must produce. */
    static Path golden(String sessionName) {
        return root().resolve("packages/chat-core/goldens/view-" + sessionName + ".json");
    }
}
