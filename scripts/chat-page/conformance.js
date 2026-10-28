// The host-port conformance run for the Eclipse adapter (plan Stage 21, step 21.4): chat-core's own checks of what a host must do,
// run in the page against the REAL adapter and the Java relay behind it. A host that fails one of them loses an agent's death or a
// settings read without anyone noticing, which is why the checks are chat-core's and not ours.
//
// Not part of the shipped page: scripts/build-chat-page.sh writes this bundle to .work/chat-conformance/, outside the plug-in. A test
// loads conformance.html in a ChatPage, waits for the `started` op, then runs `window.runConformance(spec)`; the result goes to Java as
// {op:'conformance', failures:[...]}, empty when the adapter conforms.
import { agentHostFailures, notifierFailures, syncReadFailures } from '@jawata/chat-core/test-support';
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

if (typeof window.jawataHostCall === 'function') {
  begin();
} else {
  window.addEventListener('jawata-host-ready', begin, { once: true });
}
