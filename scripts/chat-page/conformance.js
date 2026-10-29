// The host-port conformance run for the Eclipse adapter (plan Stage 21, step 21.4): chat-core's own checks of what a host must do,
// run in the page against the REAL adapter and the Java relay behind it. A host that fails one of them loses an agent's death or a
// settings read without anyone noticing, which is why the checks are chat-core's and not ours.
//
// Not part of the shipped page: scripts/build-chat-page.sh writes this bundle to .work/chat-conformance/, outside the plug-in. A test
// loads conformance.html in a ChatPage, waits for the `started` op, then runs `window.runConformance(spec)`; the result goes to Java as
// {op:'conformance', failures:[...]}, empty when the adapter conforms.
//
// The same page also PLAYS a recorded session (step 21.5): `window.playSession({answers})` builds a chat on the adapter, connects to the
// agent the Java side configured (the replay agent), sends one prompt, answers proposals as the recording's person did, and sends
// chat-core's normalised form of everything the core told the view to Java as {op:'played', body}. The test compares it with chat-core's golden.
import { createChat } from '@jawata/chat-core';
import { VIEW_CONTRACT_VERSION } from '@jawata/chat-core/view';
import { RecordingPort, agentHostFailures, answerProposals, goldenBody, notifierFailures, syncReadFailures } from '@jawata/chat-core/test-support';
import { createEclipseHost } from './host-adapter.js';
import { chatCommands, commandQueue } from './commands.js';

const bridge = {
  call(op) {
    const answer = window.jawataHostCall(JSON.stringify(op));
    return answer === null || answer === undefined ? null : JSON.parse(answer);
  },
};

let host;
/** The commands of the chat a test is playing, once there is one: Java's `command` events run them, as entry.js does. */
let commandTable;
const queue = commandQueue(() => commandTable, bridge);
window.jawataHostDeliver = (text) => {
  const message = JSON.parse(text);
  if (message.kind === 'command' && commandTable) { queue(message); } else { host?.receive(message); }
};

function begin() {
  host = createEclipseHost(bridge, bridge.call({ op: 'config' }));
  bridge.call({ op: 'started', contract: VIEW_CONTRACT_VERSION });
}

/**
 * @param {{ running: object, cannotStart: object }} spec how to start a program that stays running, and one that cannot start
 */
window.runConformance = async (spec) => {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const failures = [
    ...syncReadFailures(host.ports),
    ...await notifierFailures(host.ports.notifier),
    ...await agentHostFailures(host.ports.agents, spec.running, sleep, spec.cannotStart),
  ];
  bridge.call({ op: 'conformance', failures });
};

/**
 * @param {{ answers: string[] }} spec the option ids the recorded person chose for the agent's proposals, in order
 */
window.playSession = async (spec) => {
  const chat = createChat(host.ports);
  const port = new RecordingPort();
  chat.controller.attach(port);
  if (spec.answers.length > 0) { answerProposals(chat.controller, port, spec.answers); }
  try {
    await chat.sessions.connectToAgent('agent');
    await chat.controller.sendPrompt('replay');
    await new Promise((resolve) => setTimeout(resolve, 300));
    bridge.call({ op: 'played', body: goldenBody(port.toView) });
  } finally {
    chat.dispose();
  }
};

/**
 * Step 21.7: a chat that is driven from Java by commands (connectAgent, then sendPrompt with its text), as the Eclipse commands drive the
 * real one; the view stream is sent to Java as above once the turn has ended.
 */
window.playByCommands = async (spec = {}) => {
  const turns = spec.turns ?? 1;
  const chat = createChat(host.ports);
  const port = new RecordingPort();
  chat.controller.attach(port);
  commandTable = chatCommands(chat, (error) => {
    bridge.call({ op: 'log', message: `ERROR a prompt the person sent failed: ${error instanceof Error ? error.message : String(error)}` });
  });
  try {
    for (let i = 0; i < 400 && port.toView.filter((message) => message.type === 'promptEnd').length < turns; i++) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
    bridge.call({ op: 'played', body: goldenBody(port.toView) });
  } finally {
    commandTable = undefined;
    chat.dispose();
  }
};

/**
 * Step 21.15 (the live turn, opt-in): the same chat driven by Java commands against a REAL agent, which takes minutes and not seconds. It
 * waits until `turns` prompts have ended or `timeoutMs` has passed, then sends Java {op:'played', body, tools, ended} where `tools` lists each
 * tool call the agent made as {id, title, status} (the latest title and status the agent gave it) and `ended` is how many prompts ended.
 * @param {{ turns: number, timeoutMs: number }} spec
 */
window.playLive = async (spec) => {
  const chat = createChat(host.ports);
  const port = new RecordingPort();
  chat.controller.attach(port);
  commandTable = chatCommands(chat, (error) => {
    bridge.call({ op: 'log', message: `ERROR a prompt the person sent failed: ${error instanceof Error ? error.message : String(error)}` });
  });
  const ended = () => port.toView.filter((message) => message.type === 'promptEnd').length;
  // nobody sits at the screen of a live turn: a tool the agent asks leave for is allowed once, so the turn is not left waiting
  const post = port.post.bind(port);
  port.post = (message) => {
    post(message);
    if (message.type === 'permissionRequest') {
      const allowing = message.options.find((option) => option.kind === 'allow_once') ?? message.options.find((option) => option.kind === 'allow_always') ?? message.options[0];
      void Promise.resolve().then(() => chat.controller.handle({ type: 'answerPermission', requestId: message.requestId, optionId: allowing.optionId }));
    }
  };
  try {
    for (const started = Date.now(); Date.now() - started < spec.timeoutMs && ended() < spec.turns;) {
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
    const calls = new Map();
    for (const message of port.toView) {
      const update = message.type === 'sessionUpdate' ? message.update : undefined;
      if (update && (update.sessionUpdate === 'tool_call' || update.sessionUpdate === 'tool_call_update')) {
        const before = calls.get(update.toolCallId) ?? { id: update.toolCallId, title: '', status: '' };
        calls.set(update.toolCallId, { id: before.id, title: update.title || before.title, status: update.status || before.status });
      }
    }
    bridge.call({ op: 'played', body: goldenBody(port.toView), tools: [...calls.values()], ended: ended() });
  } finally {
    commandTable = undefined;
    chat.dispose();
  }
};

/** Step 21.7: the names of the commands the page runs, sent to Java as {op:'asked', value} (comma separated). */
window.commandNames = () => {
  bridge.call({ op: 'asked', value: Object.keys(chatCommands({ flow: {}, controller: {} })).join(',') });
};

/**
 * R6: list the agent's sessions, open (load) the one the recording loads, wait for the replay to end, and send the view stream as above.
 * @param {{ sessionId: string }} spec the session the recording loads
 */
window.loadSession = async (spec) => {
  const chat = createChat(host.ports);
  const port = new RecordingPort();
  chat.controller.attach(port);
  try {
    await chat.sessions.listSessions('agent');
    await chat.sessions.loadSession('agent', spec.sessionId);
    for (let i = 0; i < 100 && !port.toView.some((message) => message.type === 'loadSessionEnd'); i++) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
    bridge.call({ op: 'played', body: goldenBody(port.toView) });
  } finally {
    chat.dispose();
  }
};

/**
 * Step 21.9: one question or notice the core can put to its host, asked through the adapter; the answer goes to Java as {op:'asked', value}.
 * @param {{ kind: 'confirm', question: string, yes: string } | { kind: 'choose', methods: object[] } | { kind: 'configure' }
 *          | { kind: 'progress', title: string, ms: number }} spec
 */
window.ask = async (spec) => {
  let value = null;
  if (spec.kind === 'confirm') {
    value = await host.ports.chooser.confirm(spec.question, spec.yes);
  } else if (spec.kind === 'choose') {
    const picked = await host.ports.chooser.chooseAuthMethod('the agent', spec.methods);
    value = picked ? picked.id : null;
  } else if (spec.kind === 'configure') {
    await host.ports.actions.configureAgent();
    value = 'configured';
  } else if (spec.kind === 'progress') {
    value = await host.ports.notifier.progress(spec.title, async () => {
      await new Promise((resolve) => setTimeout(resolve, spec.ms));
      return 'finished';
    });
  }
  bridge.call({ op: 'asked', value });
};

/** Step 21.8: the person asks to connect while no agent is set; the core answers with its question, which the host shows. */
window.connectWithoutAgent = async () => {
  const chat = createChat(host.ports);
  chat.controller.attach(new RecordingPort());
  try {
    await chat.controller.handle({ type: 'connectAgent' });
    bridge.call({ op: 'connected' });
  } finally {
    chat.dispose();
  }
};

if (typeof window.jawataHostCall === 'function') {
  begin();
} else {
  window.addEventListener('jawata-host-ready', begin, { once: true });
}
