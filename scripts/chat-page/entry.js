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

// D53 (r): Agent Control and btw are views of their own, each a page with a role; the one core is in the chat's page and they reach it through Java
const role = new URLSearchParams(location.search).get('role');

function startPanel() {
  let hear = null;
  receive = (message) => { if (message.kind === 'view') { hear?.(message.message); } };
  early.splice(0).forEach(receive);
  mountChatView(root, {
    send(intent) { bridge.call({ op: 'fromPanel', role, intent }); },
    onMessage(handler) { hear = handler; return { dispose() { hear = null; } }; },
  }, role);
  bridge.call({ op: 'panelReady', role });
}

/** The chat's page: one port of the core for each panel view Java says has opened. */
function panelPorts(chat) {
  const ports = new Map();
  return (message) => {
    if (message.kind === 'panelOpen') {
      const port = {
        hear: null,
        closed: [],
        post(msg) { bridge.call({ op: 'toPanel', role: message.role, message: msg }); },
        onMessage(handler) { port.hear = handler; return { dispose() { port.hear = null; } }; },
        onDispose(handler) { port.closed.push(handler); return { dispose() {} }; },
      };
      ports.get(message.role)?.closed.forEach(close => close());
      ports.set(message.role, port);
      chat.controller.attachPanel(port);
    } else if (message.kind === 'panelIntent') {
      ports.get(message.role)?.hear?.(message.intent);
    } else if (message.kind === 'panelClosed') {
      ports.get(message.role)?.closed.forEach(close => close());
      ports.delete(message.role);
    }
  };
}

/**
 * D53 (r): the embedded browser of Eclipse on Linux gives the page no picture when one is pasted, so after Ctrl+V in the field, unless the page got
 * the picture itself, Java reads it from the clipboard.
 */
function pasteFromTheClipboard(link) {
  let pageHadPicture = false;
  document.addEventListener('paste', (e) => {
    pageHadPicture = [...(e.clipboardData?.items ?? [])].some(item => item.kind === 'file' && item.type.startsWith('image/'));
  }, true);
  document.addEventListener('keydown', (e) => {
    if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'v' || e.target?.tagName !== 'TEXTAREA') { return; }
    pageHadPicture = false;
    setTimeout(() => {
      if (pageHadPicture) { return; }
      const picture = bridge.call({ op: 'clipboardPicture' });
      if (picture) { link.deliver({ type: 'attachPasted', name: 'screenshot.png', mimeType: picture.mimeType, data: picture.data }); }
    }, 100);
  }, true);
}

let started = false;
function start() {
  if (started) { return; }
  started = true;
  const first = bridge.call({ op: 'theme' });
  if (first && first.css) { theme.textContent = first.css; }
  if (role) { startPanel(); return; }
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
  const panels = panelPorts(chat);
  receive = (message) => {
    if (message.kind === 'command') { queue(message); } else if (message.kind.startsWith('panel')) { panels(message); } else { events(message); }
  };
  mountChatView(root, link.page);
  pasteFromTheClipboard(link);
  window.jawataChatPage = { contract: VIEW_CONTRACT_VERSION, chat, link };
  window.addEventListener('unload', () => { chat.dispose(); });
  bridge.call({ op: 'started', contract: VIEW_CONTRACT_VERSION });
}

if (typeof window.jawataHostCall === 'function') {
  start();
} else {
  window.addEventListener('jawata-host-ready', start, { once: true });
}
