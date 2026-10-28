// Bundles the chat's page for scripts/build-chat-page.sh. Run with the scratch folder as the working directory, which holds
// entry.js and node_modules (the three packed chat packages, installed together, and the bundler). The packages ship compiled
// ES modules, so no TypeScript loader is needed.
'use strict';
const { resolve } = require('path');

module.exports = {
  mode: 'production',
  target: 'web',
  context: process.cwd(),
  entry: './entry.js',
  output: { path: resolve(process.cwd(), 'out'), filename: 'chat.js' },
  resolve: { extensions: ['.js'] },
  // a page has no `process`: the libraries' NODE_ENV checks are decided here, at build time
  optimization: { nodeEnv: 'production' },
  devtool: false,
  performance: { hints: false },
};
