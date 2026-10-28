package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Plan Stage 21, step 21.9: every kind of question the core puts to its host crosses the bridge, is shown by the dialogs, and its answer
 * comes back to the core that asked, with the person's yes, their cancel, the method they picked, or the end of the setup dialog.
 * (The real Eclipse dialogs are pressed in {@code WorkbenchDialogsTest}; here the dialogs are recorded.)
 */
public class ChatQuestionsTest {

    private static final String CONFIRM = "{\"kind\": \"confirm\", \"question\": \"Restart the agent?\", \"yes\": \"Restart\"}";

    @Test
    public void aYesAndACancelReachTheCoreThatAsked() throws Exception {
        RecordingDialogs dialogs = new RecordingDialogs();
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent(), dialogs)) {
            page.run("window.ask(" + CONFIRM + ");");
            assertEquals(true, page.waitForCall("asked").path("value").asBoolean(), "yes");
        }
        dialogs.yes = false;
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent(), dialogs)) {
            page.run("window.ask(" + CONFIRM + ");");
            assertEquals(false, page.waitForCall("asked").path("value").asBoolean(true), "cancel");
        }
        assertEquals(List.of("confirm: Restart the agent? [Restart]", "confirm: Restart the agent? [Restart]"), dialogs.asked);
    }

    @Test
    public void theMethodThePersonPickedAndTheEndOfTheSetupDialogReachTheCore() throws Exception {
        RecordingDialogs dialogs = new RecordingDialogs();
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent(), dialogs)) {
            page.run("window.ask({\"kind\": \"choose\", \"methods\": [{\"id\": \"login\", \"name\": \"Log in\"}, {\"id\": \"key\", \"name\": \"Use a key\"}]});");
            assertEquals("login", page.waitForCall("asked").path("value").asText(), "the recording dialogs pick the first");
        }
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent(), dialogs)) {
            page.run("window.ask({\"kind\": \"configure\"});");
            assertEquals("configured", page.waitForCall("asked").path("value").asText(), "the core is told when the setup dialog is closed");
        }
        assertEquals(List.of("choose: the agent [Log in, Use a key]", "configureAgent"), dialogs.asked);
    }

    @Test
    public void workInProgressIsShownWhileTheCoreWorksAndTakenAwayWhenItEnds() throws Exception {
        RecordingDialogs dialogs = new RecordingDialogs();
        try (HostTestPage page = new HostTestPage(FixedChatSettings.withoutAgent(), dialogs)) {
            page.run("window.ask({\"kind\": \"progress\", \"title\": \"Connecting to the agent…\", \"ms\": 50});");
            assertEquals("finished", page.waitForCall("asked").path("value").asText(), "the core gets the job's result back");
        }
        assertEquals(List.of("progress begun: Connecting to the agent…", "progress ended"), dialogs.asked);
    }
}
