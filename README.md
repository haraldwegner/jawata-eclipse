# jawata-eclipse

jawata inside Eclipse: the jawata engine on Eclipse's own Java tooling, jawata's chat in an Eclipse view, and a Markdown viewer
for the sprint documents. Installed from the update site this repository builds (`repository/target/repository`).

Build: `xvfb-run -a mvn -B verify` (Java 21, Maven 3.9; the tests open Eclipse windows on the off-screen display Xvfb). `.md` files
open in jawata's Markdown editor (WikiText's editor, with the specs' tables in the preview); `-Djawata.spec.md=PATH` also opens a
real spec in the tests.
End-to-end: `scripts/28k-e2e.sh` (needs the update site from the build and the jawata-mcp checkout beside this one).
After cloning, run `scripts/install-hooks.sh` once.

Licence: AGPL-3.0 with the additional permission for Eclipse components in `NOTICE`.
