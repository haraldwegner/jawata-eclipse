// The chat's page for Eclipse (plan Stage 21, step 21.1): the entry scripts/build-chat-page.sh bundles.
//
// Why this and not the VS Code webview's entry: packages/vscode/src/webview/main.ts calls acquireVsCodeApi() and mounts only the
// view, because VS Code runs the core in the extension host. Eclipse runs the core IN the page (chat-core's createChat with an
// in-process link), so this page carries chat-view and chat-core both, and no host adapter yet: step 21.4 writes it. Until then the
// page mounts the view on a bare relay and exposes the hook below.
//
// THE HOOK (window.jawataHost, defined by the host BEFORE this script runs; the SWT Browser's script-on-load or a BrowserFunction):
//   jawataHost.start(page)  optional. If it exists, the host owns the wiring: it is called once with `page` (below) and this entry
//                           mounts nothing itself. The adapter of step 21.4 is this.
//   jawataHost.send(intent) optional, used only when `start` is absent: every intent the view sends (the first is the `ready` intent
//                           carrying the contract number) is passed to it.
// THE PAGE (window.jawataChatPage, also passed to start): { contract, READY_INTENT, mountChatView, createChat, createInProcessLink,
//   root, deliver(message) }. `deliver` hands one ViewMessage to the view (the relay path; the in-process path uses its own link).
// Intents sent before `jawataHost.send` exists are kept and passed on in order when it appears (checked on `jawata-host-ready`).
import { mountChatView } from '@jawata/chat-view';
import { VIEW_CONTRACT_VERSION, READY_INTENT } from '@jawata/chat-core/view';
import { createChat, createInProcessLink } from '@jawata/chat-core';

const root = document.getElementById('root');
if (!root) { throw new Error('the page has no root element'); }

// the page fills the browser; the theme (step 21.10) is pushed into #jawata-theme
const reset = document.createElement('style');
reset.textContent = 'html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; }';
document.head.appendChild(reset);

const handlers = new Set();
const page = {
  contract: VIEW_CONTRACT_VERSION,
  READY_INTENT,
  mountChatView,
  createChat,
  createInProcessLink,
  root,
  deliver(message) { for (const handler of [...handlers]) { handler(message); } },
};
window.jawataChatPage = page;

const host = window.jawataHost;
if (host && typeof host.start === 'function') {
  host.start(page);
} else {
  const waiting = [];
  const flush = () => {
    const h = window.jawataHost;
    if (!h || typeof h.send !== 'function') { return; }
    while (waiting.length > 0) { h.send(waiting.shift()); }
  };
  window.addEventListener('jawata-host-ready', flush);
  mountChatView(root, {
    onMessage(handler) {
      handlers.add(handler);
      return { dispose: () => { handlers.delete(handler); } };
    },
    send(intent) { waiting.push(intent); flush(); },
  });
}
