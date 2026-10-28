// Writes the licence texts of the libraries a webpack bundle holds: node collect-licences.mjs <stats.json> <node_modules> <out file>.
// A library's text is the LICENSE / LICENCE / COPYING file of its package folder, or a note saying there is none (never silently
// skipped). The packages of @jawata are jawata's own code (AGPL-3.0-only, the page's LICENSE.txt) and are left out.
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [statsFile, modulesDir, outFile] = process.argv.slice(2);
const stats = JSON.parse(readFileSync(statsFile, 'utf8'));
const used = new Set();
const walk = (modules) => {
  for (const m of modules ?? []) {
    const path = (m.nameForCondition ?? m.name ?? '').replace(/\\/g, '/');
    const hit = /node_modules\/((?:@[^/]+\/)?[^/]+)\/(?!.*node_modules\/)/.exec(path);
    if (hit) { used.add(hit[1]); }
    walk(m.modules);
  }
};
walk(stats.modules);

const parts = [];
for (const name of [...used].filter(n => !n.startsWith('@jawata/')).sort()) {
  const dir = join(modulesDir, name);
  const manifest = existsSync(join(dir, 'package.json')) ? JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8')) : {};
  const file = existsSync(dir) ? readdirSync(dir).find(f => /^(licen[sc]e|copying)(\.|$)/i.test(f)) : undefined;
  const text = file ? readFileSync(join(dir, file), 'utf8').trim() : '(the package ships no licence file)';
  parts.push(`================================================================================\n${name} ${manifest.version ?? ''}\nLicence declared: ${typeof manifest.license === 'string' ? manifest.license : JSON.stringify(manifest.license ?? 'none')}\n--------------------------------------------------------------------------------\n${text}\n`);
}
writeFileSync(outFile, `Third-party libraries bundled into the jawata chat page (${parts.length}), each under its own licence.\n\n${parts.join('\n')}`);
console.log(`${parts.length} libraries`);
