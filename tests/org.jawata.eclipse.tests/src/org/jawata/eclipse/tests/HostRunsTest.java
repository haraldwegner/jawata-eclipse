package org.jawata.eclipse.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.menus.IMenuService;
import org.jawata.eclipse.host.EclipseEngine;
import org.jawata.eclipse.host.EngineStartup;
import org.jawata.eclipse.run.EclipseDebugTurn;
import org.jawata.eclipse.run.HostRuns;
import org.jawata.eclipse.run.HostRuns.JawataRun;
import org.jawata.eclipse.run.RunForJawataMenu;
import org.jawata.spi.host.McpEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan Stage 19: "Run for jawata" and the turn to debug, against a real Eclipse and a real program (contract §6.1, A3-2). A program
 * is started from the person's own run configuration; the debug agent and the recording ride a working copy that is never saved; it
 * runs in Run mode, so Eclipse's debugger never connects; jawata's debugger finds it and attaches; and when the person starts
 * Eclipse's Debug the agent's session ends and the agent does not attach again until the person is done. The fixture is a project
 * whose program loops until it is stopped.
 */
public class HostRunsTest {

    private static final String SPIN = "package p;\n\npublic class Spin {\n    public static void main(String[] args) throws Exception {\n"
        + "        int ticks = 0;\n        while (true) {\n            ticks++;\n            System.out.println(\"tick \" + ticks);\n            Thread.sleep(50);\n        }\n    }\n}\n";
    private static final String CONFIGURATION = "jawata-spin";

    private IProject project;
    private IFile saved;
    private ILaunchConfiguration person;
    private final ILaunchManager launches = DebugPlugin.getDefault().getLaunchManager();

    /** Closes the Console view if an earlier launch opened it, so each test starts from the workbench the person had. */
    private static void closeConsoleView() {
        Display.getDefault().syncExec(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            IViewReference console = page == null ? null : page.findViewReference("org.eclipse.ui.console.ConsoleView");
            if (console != null) {
                page.hideView(console);
            }
        });
    }

    @BeforeEach
    public void aProjectWithAProgramAndARunConfiguration() throws Exception {
        closeConsoleView();
        project = WorkspaceProjects.java("jawata-run-");
        project.getFolder("src/p").getFile("Spin.java").create(new ByteArrayInputStream(SPIN.getBytes(StandardCharsets.UTF_8)), true, null);
        project.build(IncrementalProjectBuilder.FULL_BUILD, null);
        assertTrue(project.getFile("bin/p/Spin.class").exists(), "the program is compiled before it is run");
        project.getFolder("launch").create(true, true, null);
        ILaunchConfigurationType type = launches.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);
        ILaunchConfigurationWorkingCopy made = type.newInstance(project.getFolder("launch"), CONFIGURATION);
        made.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, project.getName());
        made.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "p.Spin");
        person = made.doSave();
        saved = person.getFile();
        assertNotNull(saved, "the configuration is a file in the project, so its bytes can be compared");
    }

    @AfterEach
    public void everythingStartedIsStopped() throws Exception {
        for (ILaunch launch : launches.getLaunches()) {
            if (!launch.isTerminated()) {
                launch.terminate();
            }
            launches.removeLaunch(launch);
        }
        Job.getJobManager().cancel(HostRuns.FAMILY);
        Job.getJobManager().join(HostRuns.FAMILY, null);
        closeConsoleView();
        WorkspaceProjects.deleteQuietly(project);
    }

    /** The person starting Eclipse's Debug on their configuration; without a Console, so only the product's own launch can open one. */
    private ILaunch startPersonsDebug() throws Exception {
        ILaunchConfigurationWorkingCopy debug = person.getWorkingCopy();
        debug.setAttribute("org.eclipse.debug.ui.ATTR_CONSOLE_OUTPUT_ON", false);
        return debug.launch(ILaunchManager.DEBUG_MODE, null);
    }

    private byte[] savedBytes() throws Exception {
        try (InputStream in = saved.getContents()) {
            return in.readAllBytes();
        }
    }

    private ILaunch launchFor(JawataRun run) {
        return HostRuns.shared().launchOf(run).orElseThrow(() -> new AssertionError("no live launch for jawata for " + run));
    }

    private JawataRun startForJawata(String startedBy) throws Exception {
        return HostRuns.shared().run(CONFIGURATION, startedBy).get(60, TimeUnit.SECONDS);
    }

    private McpClient agent() throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        McpEndpoint at = Await.found("the listener came up", () -> engine.endpoint().reached());
        McpClient client = McpClient.connect(at.url());
        Await.found("the debug tool is listed", () -> client.toolNames().filter(names -> names.contains("debug")));
        return client;
    }

    @Test
    public void theProgramRunsWithTheDebugAgentInRunModeAndTheSavedConfigurationIsUntouched() throws Exception {
        byte[] before = savedBytes();

        JawataRun run = startForJawata(HostRuns.AGENT);
        ILaunch launch = launchFor(run);

        assertTrue(run.pid() > 0, "the program has a process id the debugger attaches by: " + run);
        String command = ProcessHandle.of(run.pid()).orElseThrow().info().commandLine().orElse("");
        assertTrue(command.contains("-agentlib:jdwp") && command.contains("suspend=n"),
            "the running program carries the debug agent and does not wait for a debugger: " + command);
        assertTrue(command.contains("StartFlightRecording"), "and the continuous recording the profiler reads: " + command);
        assertEquals(ILaunchManager.RUN_MODE, launch.getLaunchMode(), "it was started in Run mode");
        assertNull(launch.getDebugTarget(), "so Eclipse's debugger never connected to it");
        assertEquals(0, launch.getDebugTargets().length);
        assertEquals(List.of(new JawataRun(CONFIGURATION, run.pid(), HostRuns.AGENT)), HostRuns.shared().running());
        assertFalse(person.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, "").contains("jdwp"),
            "the person's configuration, as Eclipse holds it, has no agent option");
        assertTrue(java.util.Arrays.equals(before, savedBytes()), "and the saved file is byte for byte what it was");
    }

    @Test
    public void aDebugAgentOfThePersonsOwnThatWaitsForADebuggerIsReplacedSoTheProgramNeverHangs() throws Exception {
        ILaunchConfigurationWorkingCopy edited = person.getWorkingCopy();
        edited.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0");
        person = edited.doSave();

        JawataRun run = startForJawata(HostRuns.AGENT);

        String command = ProcessHandle.of(run.pid()).orElseThrow().info().commandLine().orElse("");
        assertTrue(command.contains("suspend=n"), "the program does not wait for a debugger: " + command);
        assertFalse(command.contains("suspend=y"), "the person's own agent, which would hold it, is gone: " + command);
    }

    @Test
    public void theProgramIsABackgroundJobThePersonCanCancel() throws Exception {
        JawataRun run = startForJawata(HostRuns.PERSON);

        Job[] jobs = Job.getJobManager().find(HostRuns.FAMILY);
        assertEquals(1, jobs.length, "the run is one background job");
        assertEquals("Running " + CONFIGURATION + " for jawata", jobs[0].getName(), "named for what the status bar shows");
        assertFalse(jobs[0].isUser(), "and it does not interrupt the person with a dialog");
        assertEquals(HostRuns.PERSON, HostRuns.shared().running().get(0).startedBy(), "it records that the person asked");

        jobs[0].cancel();
        assertTrue(Await.until(() -> !ProcessHandle.of(run.pid()).map(ProcessHandle::isAlive).orElse(false)), "cancelling the job stops the program");
        assertTrue(Await.until(() -> HostRuns.shared().launchOf(run).isEmpty()), "and its launch leaves the live list when its job ends");
        assertTrue(Await.until(() -> HostRuns.shared().running().isEmpty()), "and it is no longer listed as running");
    }

    @Test
    public void jawatasDebuggerFindsTheProgramAndAttaches() throws Exception {
        McpClient agent = agent();
        JawataRun run = startForJawata(HostRuns.AGENT);

        JsonNode discovered = agent.call("debug", "{\"action\":\"discover\"}");
        String found = McpClient.text(discovered);
        assertFalse(McpClient.failed(discovered), found);
        assertTrue(found.contains(Long.toString(run.pid())), "discover lists the program: " + found);

        JsonNode attached = agent.call("debug", "{\"action\":\"attach\",\"pid\":" + run.pid() + "}");
        assertFalse(McpClient.failed(attached), "the agent attaches by pid: " + McpClient.text(attached));
        assertTrue(McpClient.text(attached).contains("sessionId"), McpClient.text(attached));
    }

    /**
     * Plan Stage 25a, step 25a.3, the half that can be shown today: the hand-over smoke from the attach on. A bug is reproduced in the
     * program (here its counter), the agent attaches by pid, stops the program at the line and reports the variable's value. The agent
     * ASKING for the run (path A) is the test that follows, which needs the engine built from main.
     */
    @Test
    public void anAgentThatAttachesToTheProgramReadsTheValueOfAVariableAtABreakpoint() throws Exception {
        McpClient agent = agent();
        JawataRun run = startForJawata(HostRuns.AGENT);
        String attached = debugText(agent, "{\"action\":\"attach\",\"pid\":" + run.pid() + "}");
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"").matcher(attached);
        assertTrue(id.find(), "the attach answers a session: " + attached);
        String session = id.group(1);
        String set = debugText(agent, "{\"action\":\"breakpoint_set\",\"sessionId\":\"" + session + "\",\"kind\":\"line\",\"className\":\"p.Spin\",\"line\":8}");
        assertFalse(set.contains("\"success\":false"), "the breakpoint is set on the line after the count: " + set);

        String hit = debugText(agent, "{\"action\":\"wait\",\"sessionId\":\"" + session + "\",\"timeoutMillis\":20000}");
        java.util.regex.Matcher thread = java.util.regex.Pattern.compile("\"threadId\"\\s*:\\s*(\\d+)").matcher(hit);
        assertTrue(thread.find(), "the program stopped at the breakpoint: " + hit);
        String value = debugText(agent, "{\"action\":\"evaluate\",\"sessionId\":\"" + session + "\",\"threadId\":" + thread.group(1) + ",\"expression\":\"ticks\"}");

        assertTrue(value.matches("(?s).*\"(value|result)\"\\s*:\\s*\"?[1-9][0-9]*\"?.*"),
            "the agent reads the counter at the stop, a number above zero: " + value);
        debugText(agent, "{\"action\":\"detach\",\"sessionId\":\"" + session + "\"}");
    }

    /**
     * Plan Stage 25a, step 25a.3, path A, whole: the agent ASKS for the run. {@code debug launch configuration=} reaches this host through
     * the engine's HostRuns port, the host starts the person's configuration (the program is not held), the engine attaches by the pid the
     * host answers with, and the agent reads the counter at a breakpoint. The engine here is the one built from main (scripts/fetch-engine.sh
     * with JAWATA_ENGINE_DIST), since the 4.5.0 release has no such tool.
     */
    @Test
    public void anAgentThatAsksForARunByConfigurationNameGetsItStartedAttachedAndReadsAVariableAtABreakpoint() throws Exception {
        byte[] before = savedBytes();
        McpClient agent = agent();

        String launched = debugText(agent, "{\"action\":\"launch\",\"configuration\":\"" + CONFIGURATION + "\"}");

        assertFalse(launched.contains("\"success\":false"), "the engine had the host run the configuration: " + launched);
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"").matcher(launched);
        assertTrue(id.find(), "and attached: the answer holds a session: " + launched);
        String session = id.group(1);
        List<JawataRun> running = HostRuns.shared().running();
        assertEquals(1, running.size(), "the program the host started is the one running: " + running);
        assertEquals(HostRuns.AGENT, running.get(0).startedBy(), "and it records that the agent asked");
        assertEquals(CONFIGURATION, running.get(0).configuration());
        assertTrue(launched.contains(Long.toString(running.get(0).pid())), "the engine attached by the pid the host answered with: " + launched);
        assertEquals(ILaunchManager.RUN_MODE, launchFor(running.get(0)).getLaunchMode(), "in Run mode, so Eclipse's debugger never connects");

        String set = debugText(agent, "{\"action\":\"breakpoint_set\",\"sessionId\":\"" + session + "\",\"kind\":\"line\",\"className\":\"p.Spin\",\"line\":8}");
        assertFalse(set.contains("\"success\":false"), "the breakpoint is set on the line after the count: " + set);
        String hit = debugText(agent, "{\"action\":\"wait\",\"sessionId\":\"" + session + "\",\"timeoutMillis\":20000}");
        java.util.regex.Matcher thread = java.util.regex.Pattern.compile("\"threadId\"\\s*:\\s*(\\d+)").matcher(hit);
        assertTrue(thread.find(), "the program stopped at the breakpoint: " + hit);
        String value = debugText(agent, "{\"action\":\"evaluate\",\"sessionId\":\"" + session + "\",\"threadId\":" + thread.group(1) + ",\"expression\":\"ticks\"}");
        assertTrue(value.matches("(?s).*\"(value|result)\"\\s*:\\s*\"?[1-9][0-9]*\"?.*"),
            "the agent reads the counter at the stop, a number above zero: " + value);
        debugText(agent, "{\"action\":\"detach\",\"sessionId\":\"" + session + "\"}");
        assertTrue(java.util.Arrays.equals(before, savedBytes()), "and the person's saved configuration is byte for byte what it was");
    }

    @Test
    public void askingForAConfigurationThatDoesNotExistIsAnErrorThatNamesItAndStartsNothing() throws Exception {
        McpClient agent = agent();

        String answer = debugText(agent, "{\"action\":\"launch\",\"configuration\":\"no-such-configuration\"}");

        assertTrue(answer.contains("no-such-configuration"), "the refusal names what was asked for: " + answer);
        assertTrue(answer.contains("HOST_RUN_FAILED"), "and the engine reports the host's refusal as such: " + answer);
        assertTrue(HostRuns.shared().running().isEmpty(), "and no program was started");
    }

    @Test
    public void discoverLabelsAProgramThePersonStartedWithRunForJawataWithItsConfigurationAndWhoStartedIt() throws Exception {
        McpClient agent = agent();
        JawataRun run = startForJawata(HostRuns.PERSON);

        String found = debugText(agent, "{\"action\":\"discover\"}");

        assertTrue(found.contains("startedForJawata"), "discover marks the program as started for jawata: " + found);
        assertTrue(found.contains(Long.toString(run.pid())) && found.contains(CONFIGURATION), "with its pid and the person's configuration: " + found);
        assertTrue(found.contains("started by the person for jawata"), "and says that the person started it: " + found);
    }

    @Test
    public void theHostAnswersTheEnginesPortWithTheProgramsItStartedAndRefusesWhatItCannotRun() throws Exception {
        EclipseEngine engine = Await.found("the workbench started the engine", EngineStartup::running);
        org.jawata.spi.host.HostRuns port = engine.engine().orElseThrow().host().instruments().runs()
            .orElseThrow(() -> new AssertionError("the host offers the engine no runs"));

        org.jawata.spi.host.HostRuns.RunForJawata run = port.runForJawata(CONFIGURATION, new org.eclipse.core.runtime.NullProgressMonitor());

        assertTrue(run.pid() > 0, "the port answers the process id the debugger attaches by: " + run);
        assertEquals(CONFIGURATION, run.configuration());
        assertEquals(org.jawata.spi.host.HostRuns.AGENT, run.startedBy(), "a run the engine asked for is the agent's");
        assertEquals(List.of(run), port.runningForJawata(), "and it is listed as running");
        org.eclipse.core.runtime.CoreException refused = org.junit.jupiter.api.Assertions.assertThrows(org.eclipse.core.runtime.CoreException.class,
            () -> port.runForJawata("no-such-configuration", new org.eclipse.core.runtime.NullProgressMonitor()));
        assertTrue(refused.getStatus().getMessage().contains("no-such-configuration"), "an unknown configuration is refused by name: " + refused.getStatus().getMessage());
        assertEquals(1, port.runningForJawata().size(), "and the refusal started nothing");
    }

    @Test
    public void whenThePersonStartsEclipsesDebugTheAgentsSessionEndsAndItDoesNotAttachUntilThePersonIsDone() throws Exception {
        McpClient agent = agent();
        JawataRun run = startForJawata(HostRuns.AGENT);
        String attached = McpClient.text(agent.call("debug", "{\"action\":\"attach\",\"pid\":" + run.pid() + "}"));
        assertTrue(attached.contains("sessionId"), attached);

        ILaunch persons = startPersonsDebug();

        assertTrue(Await.until(() -> !debugText(agent, "{\"action\":\"status\"}").contains("sessionId")),
            "the agent's session ended when the person started debugging");
        String refused = McpClient.text(agent.call("debug", "{\"action\":\"attach\",\"pid\":" + run.pid() + "}"));
        assertTrue(refused.contains("PERSON_IS_DEBUGGING"), "and the agent does not attach while the person debugs: " + refused);

        persons.terminate();
        assertTrue(Await.until(() -> debugText(agent, "{\"action\":\"attach\",\"pid\":" + run.pid() + "}").contains("sessionId")),
            "once the person is done the agent may attach again");
    }

    private static String debugText(McpClient agent, String arguments) {
        try {
            return McpClient.text(agent.call("debug", arguments));
        } catch (Exception failed) {
            throw new IllegalStateException("the debug tool could not be called: " + failed.getMessage(), failed);
        }
    }

    @Test
    public void theDebugTurnIsThePersonsExactlyWhileTheyHaveADebugLaunchAndRunForJawataIsNotOne() throws Exception {
        AtomicInteger told = new AtomicInteger();
        try (EclipseDebugTurn turn = new EclipseDebugTurn(launches, org.eclipse.core.runtime.Platform.getLog(getClass()), "org.jawata.eclipse")) {
            turn.onPersonStartsDebugging(told::incrementAndGet);
            assertFalse(turn.personIsDebugging(), "nobody is debugging before anyone starts");

            startForJawata(HostRuns.AGENT);
            assertFalse(turn.personIsDebugging(), "a program run for jawata is not the person debugging");
            assertEquals(0, told.get(), "and the engine is not told");

            // a plain Run the person starts is registered with Eclipse, unlike a program run for jawata; it is not debugging either
            ILaunchConfigurationWorkingCopy plain = person.getWorkingCopy();
            plain.setAttribute("org.eclipse.debug.ui.ATTR_CONSOLE_OUTPUT_ON", false);
            ILaunch plainRun = plain.launch(ILaunchManager.RUN_MODE, null);
            assertFalse(turn.personIsDebugging(), "a plain Run the person started is not debugging");
            assertEquals(0, told.get(), "and the engine is not told for it either");
            plainRun.terminate();

            ILaunch persons = startPersonsDebug();
            assertTrue(turn.personIsDebugging(), "the person's own debug launch is");
            assertEquals(1, told.get(), "and the engine is told once");

            persons.terminate();
            assertFalse(turn.personIsDebugging(), "the turn is the agent's again when the person's launch has ended");
        }
    }

    @Test
    public void startingAProgramForJawataDoesNotSwitchPerspectiveOrTakeTheFocus() throws Exception {
        AtomicReference<String> before = new AtomicReference<>();
        AtomicReference<Shell> shellBefore = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            before.set(screen());
            shellBefore.set(Display.getDefault().getActiveShell());
        });

        JawataRun started = startForJawata(HostRuns.AGENT);
        // the program writes to its output at once; a view that opens on output opens within moments, so watch for five seconds
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicReference<String> seen = new AtomicReference<>(before.get());
        while (System.nanoTime() < deadline && seen.get().equals(before.get())) {
            Thread.sleep(200);
            Display.getDefault().syncExec(() -> seen.set(screen()));
        }

        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<Shell> shellAfter = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            after.set(screen());
            shellAfter.set(Display.getDefault().getActiveShell());
        });
        assertEquals(before.get(), after.get(), "the person's perspective, the part with the focus and the Console view are as they left them; "
            + consoleDiagnosis(started));
        assertEquals(shellBefore.get(), shellAfter.get(), "and the window that had the focus still has it");
    }

    private String consoleDiagnosis(JawataRun run) throws Exception {
        ILaunch launch = launchFor(run);
        ILaunchConfiguration used = launch.getLaunchConfiguration();
        StringBuilder consoles = new StringBuilder();
        for (org.eclipse.ui.console.IConsole console : org.eclipse.ui.console.ConsolePlugin.getDefault().getConsoleManager().getConsoles()) {
            consoles.append('[').append(console.getName()).append(']');
        }
        return "the launch's configuration is a working copy: " + used.isWorkingCopy() + ", its Console switch: "
            + used.getAttribute("org.eclipse.debug.ui.ATTR_CONSOLE_OUTPUT_ON", true) + ", processes " + launch.getProcesses().length + ", consoles " + consoles;
    }

    /** What the person sees of the workbench: the perspective, the part that has the focus, and whether the Console view is open. */
    private static String screen() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (page == null || page.getPerspective() == null) {
            return "none";
        }
        IWorkbenchPart part = page.getActivePart();
        return page.getPerspective().getId() + " | active part " + (part == null ? "none" : part.getSite().getId()) + " | console view open "
            + (page.findViewReference("org.eclipse.ui.console.ConsoleView") != null);
    }

    @Test
    public void theRunMenuListsThePersonsConfigurationAndChoosingItStartsItForJawata() throws Exception {
        RunForJawataMenu menu = new RunForJawataMenu();
        IContributionItem[] items = menuItems(menu);

        ActionContributionItem ours = null;
        for (IContributionItem item : items) {
            if (item instanceof ActionContributionItem action && CONFIGURATION.equals(action.getAction().getText())) {
                ours = action;
            }
        }
        assertNotNull(ours, "the menu lists the person's run configuration");
        assertTrue(ours.getAction().getToolTipText().contains("not changed"), "and says what choosing it does: " + ours.getAction().getToolTipText());

        ours.getAction().run();

        assertTrue(Await.until(() -> HostRuns.shared().running().size() == 1), "choosing it starts the program for jawata");
        assertEquals(HostRuns.PERSON, HostRuns.shared().running().get(0).startedBy(), "as the person's own request");
        assertNotEquals(-1L, HostRuns.shared().running().get(0).pid());
    }

    @Test
    public void withNoRunConfigurationTheSubmenuSaysWhatIsMissingInsteadOfBeingEmpty() throws Exception {
        person.delete();

        IContributionItem[] items = menuItems(new RunForJawataMenu());

        assertEquals(1, items.length, "one entry that explains");
        ActionContributionItem only = (ActionContributionItem) items[0];
        assertFalse(only.getAction().isEnabled(), "it cannot be chosen, it only explains");
        assertTrue(only.getAction().getText().contains("Run > Run Configurations"), "and says where a configuration is made: " + only.getAction().getText());
    }

    @Test
    public void theWorkbenchsRunMenuCarriesRunForJawata() {
        AtomicReference<Boolean> carried = new AtomicReference<>(false);
        Display.getDefault().syncExec(() -> {
            MenuManager manager = new MenuManager();
            IMenuService menus = PlatformUI.getWorkbench().getService(IMenuService.class);
            menus.populateContributionManager(manager, "menu:org.eclipse.ui.run");
            carried.set(manager.find("org.jawata.eclipse.run.forJawata") != null);
            menus.releaseContributions(manager);
            manager.dispose();
        });
        assertTrue(carried.get(), "the Run menu of the workbench holds Run for jawata, as plugin.xml says");
    }

    private static IContributionItem[] menuItems(RunForJawataMenu menu) throws Exception {
        java.lang.reflect.Method method = RunForJawataMenu.class.getDeclaredMethod("getContributionItems");
        method.setAccessible(true);
        return (IContributionItem[]) method.invoke(menu);
    }
}
