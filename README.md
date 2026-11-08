# jawata-eclipse

jawata inside Eclipse: the jawata engine on Eclipse's own Java tooling, jawata's chat in an Eclipse view, and a Markdown viewer
for the sprint documents. Installed from the update site this repository builds (`repository/target/repository`).

The engine is never built here. `engine.version` names one released jawata-mcp; run `scripts/fetch-engine.sh` first (needs curl, jq,
tar) and it downloads that release, checks it against GitHub's digest and copies the engine's bundles to `.work/engine/plugins`,
where the target platform reads them. To move to a newer engine, change `engine.version` and run it again. To build and test against
an engine that is ahead of its last release, build the engine's dist and run `JAWATA_ENGINE_DIST=<the dist folder> scripts/fetch-engine.sh`
(the folder with `bundles/`, `tools/`, `test-bundles/` and `jawata.jar`): nothing is downloaded and no digest is checked, because none
exists for a local build, and the script says so; a run without the variable goes back to the release. `scripts/test-fetch-engine.sh`
tests both routes. The build fails at the
engine feature ("could not be found") if the script has not run; on Windows run it from Git Bash. The engine's own JaCoCo is not
shipped: the plug-in requires JaCoCo from Eclipse's release repository, so installing needs that repository to be reachable and
the engine runs on the JaCoCo version Eclipse offers.

Build: `xvfb-run -a mvn -B verify` (Java 21, Maven 3.9; the tests open Eclipse windows on the off-screen display Xvfb). `.md` files
open in jawata's Markdown editor (WikiText's editor, with the specs' tables in the preview); `-Djawata.spec.md=PATH` also opens a
real spec in the tests.
Release: pushing a tag `vMAJOR.MINOR.MICRO` runs `.github/workflows/release.yml`, which builds and tests as above (`mvn verify` took 4 minutes 9 seconds
on the author's machine with the engine and chat page already fetched; a runner also fetches the engine, packs the chat and downloads the target platform), checks the site with
`scripts/check-update-site.sh repository/target/repository <version>` and attaches `jawata-eclipse-update-site-<version>.zip` to a release.
jawata.org/eclipse/ is assembled from those releases by the Pages workflow of jawata-mcp, which lists every version still kept there;
people add `https://jawata.org/eclipse/` once under Help > Install New Software. A manual run of the workflow is a dry run that publishes nothing.
End-to-end: `scripts/28k-e2e.sh` (needs the update site from the build and the jawata-mcp checkout beside this one).
After cloning, run `scripts/install-hooks.sh` once.

Licence: AGPL-3.0 with the additional permission for Eclipse components in `NOTICE`.
