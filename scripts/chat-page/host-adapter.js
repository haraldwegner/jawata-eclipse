// The chat's host ports for Eclipse (plan Stage 21, step 21.4), implemented IN THE PAGE: the core runs here, so what it asks of its host
// is answered here or, where only Java can answer, passed over the one bridge the SWT Browser gives.
//
// THE BRIDGE, two directions, both JSON text:
//   page to Java   bridge.call(op): runs `jawataHostCall(text)` on the user interface thread and returns its answer at once (a JSON text or
//                  null). Everything the core needs answered AT ONCE is read this way, once, before the core starts (ledger 28i-C11-5):
//                  {op:'config'}. The rest are notices or requests whose answer comes later by the other direction.
//   Java to page   window.jawataHostDeliver(text) -> receive(message): {kind:'agentMessage'|'agentClosed'|'agentError'|'answer'|'settings'}.
//
// HOW EACH PORT CROSSES (chat-core's HOST_CALLS is the record; this follows it member by member):
//   agents.spawnAgent / killAgent   at once: the handle and its streams exist when spawnAgent returns; Java starts the program meanwhile
//   agents.onClosed / onError       listeners, told by Java's events
//   settings, sinks, clientInfo     at once: read from the preloaded config; `settings` events update them
//   chooser, actions                promises: a request with a ticket, answered by an `answer` event
//   notifier.progress               a promise: runs its job here and tells Java when it starts and ends
//   log, notifier.info / error      notices: no answer
// The agent's stream carries the protocol's messages framed as the SDK frames them (newline-delimited JSON); Java relays one line as one
// message and reads none of it.
import { ndJsonStream } from '@agentclientprotocol/sdk';
import { Emitter } from '@jawata/chat-core';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/**
 * @param {{ call(op: object): any }} bridge sends one op to Java and returns its parsed answer (or null)
 * @param {{ agents: Record<string, {command: string, args?: string[], env?: Record<string,string>}>, cwd: string,
 *           agentSetup: string, clientInfo: {name: string, version: string} }} config what Java preloaded
 * @returns {{ ports: import('@jawata/chat-core').HostPorts, receive(message: any): void }}
 */
export function createEclipseHost(bridge, config) {
  const settings = { agents: config.agents ?? {}, cwd: config.cwd ?? '', agentSetup: config.agentSetup ?? 'the chat preferences' };
  const emitter = new Emitter();
  /** @type {Map<string, { controller: ReadableStreamDefaultController<Uint8Array> | undefined }>} */
  const running = new Map();
  /** @type {Map<string, (value: any) => void>} */
  const tickets = new Map();
  let nextAgent = 0;
  let nextTicket = 0;

  const ask = (op) => new Promise((resolve) => {
    const ticket = `t${++nextTicket}`;
    tickets.set(ticket, resolve);
    bridge.call({ ...op, ticket });
  });

  const agents = {
    spawnAgent(name, entry, cwd) {
      const id = `agent_${++nextAgent}`;
      const record = { controller: undefined };
      const fromAgent = new ReadableStream({ start(controller) { record.controller = controller; } });
      let pending = '';
      const toAgent = new WritableStream({
        write(chunk) {
          pending += decoder.decode(chunk, { stream: true });
          let newline;
          while ((newline = pending.indexOf('\n')) >= 0) {
            const line = pending.slice(0, newline).replace(/\r$/, '');
            pending = pending.slice(newline + 1);
            if (line.length > 0) { bridge.call({ op: 'write', id, message: line }); }
          }
        },
      });
      running.set(id, record);
      bridge.call({ op: 'spawn', id, name, command: entry.command, args: entry.args ?? [], env: entry.env ?? {}, cwd: cwd ?? settings.cwd });
      return { id, name, stream: ndJsonStream(toAgent, fromAgent) };
    },
    killAgent(agentId) {
      if (!running.has(agentId)) { return false; }
      running.delete(agentId);
      return bridge.call({ op: 'kill', id: agentId }) === true;
    },
    onClosed(listener) { return emitter.listen('agent-closed', listener); },
    onError(listener) { return emitter.listen('agent-error', listener); },
  };

  const ports = {
    agents,
    settings: {
      agents: () => settings.agents,
      workspaceCwd: () => settings.cwd,
      get agentSetup() { return settings.agentSetup; },
    },
    chooser: {
      confirm: async (question, yes) => (await ask({ op: 'confirm', question, yes })) === true,
      chooseAuthMethod: async (agentName, methods) => {
        const id = await ask({ op: 'chooseAuthMethod', agentName, methods: methods.map(m => ({ id: m.id, name: m.name, description: m.description ?? null })) });
        return methods.find(m => m.id === id);
      },
      confirmAuthMethod: async (agentName, method) => (await ask({ op: 'confirmAuthMethod', agentName, method: { id: method.id, name: method.name, description: method.description ?? null } })) === true,
    },
    log: {
      log: (message) => { bridge.call({ op: 'log', message }); },
      logError: (message, error) => { bridge.call({ op: 'log', message: `ERROR ${message} ${error instanceof Error ? error.message : String(error ?? '')}`.trim() }); },
    },
    notifier: {
      async progress(title, job) {
        const ticket = `p${++nextTicket}`;
        bridge.call({ op: 'progress', phase: 'begin', ticket, title });
        try { return await job(); } finally { bridge.call({ op: 'progress', phase: 'end', ticket }); }
      },
      info: (message) => { bridge.call({ op: 'info', message }); },
      error: (message) => { bridge.call({ op: 'error', message }); },
    },
    actions: { configureAgent: async () => { await ask({ op: 'configureAgent' }); } },
    sinks: () => [],
    clientInfo: { name: config.clientInfo?.name ?? 'eclipse-jawata-chat', version: config.clientInfo?.version ?? '0' },
  };

  function receive(message) {
    switch (message.kind) {
      case 'agentMessage': {
        const record = running.get(message.id);
        record?.controller?.enqueue(encoder.encode(`${message.message}\n`));
        break;
      }
      case 'agentClosed': {
        const record = running.get(message.id);
        running.delete(message.id);
        try { record?.controller?.close(); } catch { /* already closed */ }
        emitter.emit('agent-closed', { agentId: message.id, code: message.code ?? null });
        break;
      }
      case 'agentError':
        emitter.emit('agent-error', { agentId: message.id, error: new Error(message.error) });
        break;
      case 'answer': {
        const resolve = tickets.get(message.ticket);
        tickets.delete(message.ticket);
        resolve?.(message.value ?? null);
        break;
      }
      case 'settings':
        if (message.agents !== undefined) { settings.agents = message.agents; }
        if (message.cwd !== undefined) { settings.cwd = message.cwd; }
        if (message.agentSetup !== undefined) { settings.agentSetup = message.agentSetup; }
        break;
      default:
        bridge.call({ op: 'log', message: `ERROR the page was sent a message it does not know: ${String(message.kind)}` });
    }
  }

  return { ports, receive };
}
