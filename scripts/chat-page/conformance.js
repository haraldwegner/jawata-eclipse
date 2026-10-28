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
import { RecordingPort, agentHostFailures, answerProposals, goldenBody, notifierFailures, syncReadFailures } from '@jawata/chat-core/test-support';
import { createEclipseHost } from './host-adapter.js';

const bridge = {
  call(op) {
    const answer = window.jawataHostCall(JSON.stringify(op));
    return answer === null || answer === undefined ? null : JSON.parse(answer);
  },
};

let host;
window.jawataHostDeliver = (text) => { host?.receive(JSON.parse(text)); };

function begin() {
  host = createEclipseHost(bridge, bridge.call({ op: 'config' }));
  bridge.call({ op: 'started', contract: 0 });
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

if (typeof window.jawataHostCall === 'function') {
  begin();
} else {
  window.addEventListener('jawata-host-ready', begin, { once: true });
}
