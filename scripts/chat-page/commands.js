// What the person starts from outside the page (plan Stage 21, step 21.7; ledger 28i-C56-3): every chat command a harness needs, by name,
// with its arguments. Java sends {kind:'command', name, args} when an Eclipse command runs; this table is what the page does about it. The
// VS Code extension's table (packages/vscode/src/commands.ts) is the model: what the person starts on the conversation is the chat's own
// flow and controller, so a command gives exactly the stream the page's own intent gives.
/**
 * @param {{ flow: any, controller: any }} chat the chat built by createChat
 * @param {(error: unknown) => void} [fail] told when a prompt the person sent fails, after its command has returned
 * @returns {Record<string, (args: Record<string, string>) => Promise<void> | void>}
 */
export function chatCommands(chat, fail = () => {}) {
  const { flow, controller } = chat;
  return {
    connectAgent: () => flow.connect(),
    newConversation: () => flow.newConversation(),
    disconnectAgent: () => flow.disconnect(),
    restartAgent: () => flow.restart(),
    /** Given a session's id that session is opened; otherwise the sessions the agent has are shown. */
    openSession: async (args) => {
      if (args.sessionId) { await flow.open(args.sessionId); } else { await controller.showHistory(); }
    },
    /**
     * Without a text the command only shows the chat, which Java has done by then. The command returns once the prompt is sent: a turn
     * can run for minutes, and the commands after it (a second prompt, which the chat queues, or a cancel) must not wait behind it.
     */
    sendPrompt: (args) => {
      if (args.text) { Promise.resolve().then(() => controller.sendPrompt(args.text)).catch(fail); }
    },
    cancelTurn: () => controller.handle({ type: 'cancelTurn' }),
    setMode: (args) => controller.handle({ type: 'setMode', modeId: args.modeId }),
    setModel: (args) => controller.handle({ type: 'setModel', modelId: args.modelId }),
    attachFile: (args) => { controller.attachFile(args.path); },
  };
}

/**
 * Commands run one after the other, in the order the host sent them: a prompt sent right after a connect waits for the connect to finish.
 * @param {() => Record<string, Function> | undefined} table the commands, read when each one runs
 * @param {{ call(op: object): any }} bridge
 * @returns {(message: { name: string, args?: Record<string, string> }) => void}
 */
export function commandQueue(table, bridge) {
  let last = Promise.resolve();
  return (message) => {
    last = last.then(() => {
      const commands = table();
      return commands ? runCommand(commands, bridge, message) : undefined;
    });
  };
}

/**
 * Runs one command the host sent; a failure is told to the host's log, never thrown into the page.
 * @param {Record<string, Function>} table the commands
 * @param {{ call(op: object): any }} bridge
 * @param {{ name: string, args?: Record<string, string> }} message
 */
export async function runCommand(table, bridge, message) {
  const command = table[message.name];
  if (!command) {
    bridge.call({ op: 'log', message: `ERROR the chat was sent a command it does not know: ${message.name}` });
    return;
  }
  try {
    await command(message.args ?? {});
  } catch (error) {
    bridge.call({ op: 'log', message: `ERROR the command ${message.name} failed: ${error instanceof Error ? error.message : String(error)}` });
  }
}
