# jawata-eclipse

jawata inside Eclipse: the jawata engine on Eclipse's own Java tooling, jawata's chat in an Eclipse view, and a Markdown viewer
for the sprint documents. Installed from the update site this repository builds (`repository/target/repository`).

Build: `xvfb-run mvn -B verify` (Java 21, Maven 3.9; the tests open Eclipse windows on the off-screen display Xvfb).
After cloning, run `scripts/install-hooks.sh` once.

Licence: AGPL-3.0 with the additional permission for Eclipse components in `NOTICE`.
