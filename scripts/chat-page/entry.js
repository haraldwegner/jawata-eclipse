// The chat's page for Eclipse (plan Stage 21): the entry scripts/build-chat-page.sh bundles.
//
// Why this and not the VS Code webview's entry: packages/vscode/src/webview/main.ts calls acquireVsCodeApi() and mounts only the
// view, because VS Code runs the core in the extension host. Eclipse runs the core IN the page: chat-core's createChat on the host
// ports of ./host-adapter.js, joined to the view by chat-core's in-process link. Java is reached over two functions the SWT Browser
// gives this page, nothing else:
//   window.jawataHostCall(text) -> text|null   defined by the Java side (a BrowserFunction); answers at once
//   window.jawataHostDeliver(text)             defined here; Java calls it with each event for the page
// The chat starts when the first exists: at once if it is there when this script runs, else when Java says `jawata-host-ready`
// (the page's load completed).
// THE PAGE'S HANDLE (window.jawataChatPage): { contract, chat, link }, for a test or a command to drive intents through `link.deliver`
// and to watch what the core tells the view through `link.page.onMessage`.
import { mountChatView } from '@jawata/chat-view';
import { VIEW_CONTRACT_VERSION } from '@jawata/chat-core/view';
import { createChat, createInProcessLink } from '@jawata/chat-core';
import { createEclipseHost } from './host-adapter.js';
import { chatCommands, commandQueue } from './commands.js';

const root = document.getElementById('root');
if (!root) { throw new Error('the page has no root element'); }

// the page fills the browser
const reset = document.createElement('style');
reset.textContent = 'html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; }';
document.head.appendChild(reset);

// the Eclipse theme (step 21.10): Java sends {kind:'theme', css} at the start and whenever the person changes the theme; the css sets the
// view's neutral inputs (--jc-host-*), and replacing it restyles the open view at once
const theme = document.createElement('style');
theme.id = 'jawata-theme';
document.head.appendChild(theme);

const bridge = {
  call(op) {
    const answer = window.jawataHostCall(JSON.stringify(op));
    return answer === null || answer === undefined ? null : JSON.parse(answer);
  },
};

// events Java delivers before the chat exists are kept, in order
const early = [];
let receive = (message) => { early.push(message); };
window.jawataHostDeliver = (text) => {
  const message = JSON.parse(text);
  if (message.kind === 'theme') { theme.textContent = message.css; } else { receive(message); }
};

let started = false;
function start() {
  if (started) { return; }
  started = true;
  const first = bridge.call({ op: 'theme' });
  if (first && first.css) { theme.textContent = first.css; }
  const host = createEclipseHost(bridge, bridge.call({ op: 'config' }));
  receive = host.receive;
  early.splice(0).forEach(receive);
  const chat = createChat(host.ports);
  const link = createInProcessLink();
  chat.controller.attach(link.port);
  const commands = chatCommands(chat, (error) => {
    bridge.call({ op: 'log', message: `ERROR a prompt the person sent failed: ${error instanceof Error ? error.message : String(error)}` });
  });
  const queue = commandQueue(() => commands, bridge);
  const events = host.receive;
  receive = (message) => { if (message.kind === 'command') { queue(message); } else { events(message); } };
  mountChatView(root, link.page);
  window.jawataChatPage = { contract: VIEW_CONTRACT_VERSION, chat, link };
  window.addEventListener('unload', () => { chat.dispose(); });
  bridge.call({ op: 'started', contract: VIEW_CONTRACT_VERSION });
}

if (typeof window.jawataHostCall === 'function') {
  start();
} else {
  window.addEventListener('jawata-host-ready', start, { once: true });
}
