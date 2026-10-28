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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
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
        + "        int ticks = 0;\n        while (true) {\n            ticks++;\n            Thread.sleep(50);\n        }\n    }\n}\n";
    private static final String CONFIGURATION = "jawata-spin";

    private IProject project;
    private IFile saved;
    private ILaunchConfiguration person;
    private final ILaunchManager launches = DebugPlugin.getDefault().getLaunchManager();

    @BeforeEach
    public void aProjectWithAProgramAndARunConfiguration() throws Exception {
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
        Job.getJobManager().join(HostRuns.FAMILY, null);
        WorkspaceProjects.deleteQuietly(project);
    }

    private byte[] savedBytes() throws Exception {
        try (InputStream in = saved.getContents()) {
            return in.readAllBytes();
        }
    }

    private ILaunch launchFor(JawataRun run) {
        for (ILaunch launch : launches.getLaunches()) {
            if (CONFIGURATION.equals(launch.getAttribute(HostRuns.FOR_JAWATA))) {
                return launch;
            }
        }
        throw new AssertionError("no launch marked for jawata for " + run);
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
    public void theProgramIsABackgroundJobThePersonCanCancel() throws Exception {
        JawataRun run = startForJawata(HostRuns.PERSON);

        Job[] jobs = Job.getJobManager().find(HostRuns.FAMILY);
        assertEquals(1, jobs.length, "the run is one background job");
        assertEquals("Running " + CONFIGURATION + " for jawata", jobs[0].getName(), "named for what the status bar shows");
        assertFalse(jobs[0].isUser(), "and it does not interrupt the person with a dialog");
        assertEquals(HostRuns.PERSON, HostRuns.shared().running().get(0).startedBy(), "it records that the person asked");

        jobs[0].cancel();
        assertTrue(Await.until(() -> launchFor(run).isTerminated()), "cancelling the job stops the program");
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

    @Test
    public void whenThePersonStartsEclipsesDebugTheAgentsSessionEndsAndItDoesNotAttachUntilThePersonIsDone() throws Exception {
        McpClient agent = agent();
        JawataRun run = startForJawata(HostRuns.AGENT);
        String attached = McpClient.text(agent.call("debug", "{\"action\":\"attach\",\"pid\":" + run.pid() + "}"));
        assertTrue(attached.contains("sessionId"), attached);

        ILaunch persons = person.launch(ILaunchManager.DEBUG_MODE, null);

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

            ILaunch persons = person.launch(ILaunchManager.DEBUG_MODE, null);
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
            before.set(perspective());
            shellBefore.set(Display.getDefault().getActiveShell());
        });

        startForJawata(HostRuns.AGENT);
        Thread.sleep(1_000);

        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<Shell> shellAfter = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            after.set(perspective());
            shellAfter.set(Display.getDefault().getActiveShell());
        });
        assertEquals(before.get(), after.get(), "the person's perspective is the one they left");
        assertEquals(shellBefore.get(), shellAfter.get(), "and the window that had the focus still has it");
    }

    private static String perspective() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        return page == null || page.getPerspective() == null ? "none" : page.getPerspective().getId();
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

    private static IContributionItem[] menuItems(RunForJawataMenu menu) throws Exception {
        java.lang.reflect.Method method = RunForJawataMenu.class.getDeclaredMethod("getContributionItems");
        method.setAccessible(true);
        return (IContributionItem[]) method.invoke(menu);
    }
}
