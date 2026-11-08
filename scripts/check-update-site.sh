#!/usr/bin/env bash
# Checks a built update site before it is released: usage  scripts/check-update-site.sh <site folder> <version, e.g. 0.2.0>
# (the site is repository/target/repository after `xvfb-run -a mvn -B verify`).
#
# It proves, by reading the site's files and nothing else:
#   - it is a p2 repository (content and artifacts, jar or xz);
#   - the jawata feature and the engine feature are in features/ at the version given (<version>.<qualifier>), and the metadata names the
#     jawata feature group at that version;
#   - the three plug-ins (org.jawata.eclipse, .chat, .terminal) are in plugins/ at that version;
#   - the engine's bundles and the two libraries are in plugins/ at the versions engine.version and the engine feature name;
#   - none of the bundles Eclipse brings itself is shipped (JDT, LTK, SWT, JFace, an slf4j provider, jdtpatch, resident: contract section 16).
# It does NOT install the site into an Eclipse; scripts/28k-e2e.sh does that and needs the engine checkout.
# Exit 0 when every check holds, 1 otherwise; each failed check is printed.
set -uo pipefail

site="${1:-}"; version="${2:-}"
[ -d "$site" ] && [ -n "$version" ] || { echo "usage: $0 <site folder> <version>" >&2; exit 2; }
here="$(cd "$(dirname "$0")/.." && pwd)"
engine="$(tr -d '[:space:]' < "$here/engine.version")"
fail=0
bad() { echo "  FAIL  $*"; fail=1; }
ok()  { echo "  ok    $*"; }

for f in content artifacts; do
    if [ -f "$site/$f.jar" ] || [ -f "$site/$f.xml.xz" ] || [ -f "$site/$f.xml" ]; then ok "$f metadata present"; else bad "no $f.jar / $f.xml.xz in $site"; fi
done

has() {  # $1 folder  $2 file-name prefix, with the version following
    ls "$site/$1/" 2>/dev/null | grep -Eq "^$2_$3(\.[A-Za-z0-9_-]+)?\.jar$"
}
for id in org.jawata.eclipse.feature org.jawata.engine.feature; do
    if has features "$id" "$(printf '%s' "$version" | sed 's/\./\\./g')"; then ok "feature $id $version.*"; else bad "feature $id at $version is not in features/ ($(ls "$site/features" 2>/dev/null | tr '\n' ' '))"; fi
done
for id in org.jawata.eclipse org.jawata.eclipse.chat org.jawata.eclipse.terminal; do
    if has plugins "$id" "$(printf '%s' "$version" | sed 's/\./\\./g')"; then ok "plug-in $id $version.*"; else bad "plug-in $id at $version is not in plugins/"; fi
done
for id in org.jawata.spi org.jawata.core org.jawata.mcp org.jawata.catalogue; do
    if has plugins "$id" "$(printf '%s' "$engine" | sed 's/\./\\./g')"; then ok "engine bundle $id $engine"; else bad "engine bundle $id at $engine (engine.version) is not in plugins/"; fi
done
for id in com.h2database com.fasterxml.jackson.core.jackson-annotations com.fasterxml.jackson.core.jackson-core com.fasterxml.jackson.core.jackson-databind; do
    if ls "$site/plugins/" 2>/dev/null | grep -q "^${id}_"; then ok "library $id"; else bad "library $id is not in plugins/"; fi
done

# the metadata names the feature group at the tag's version (read from content.jar's content.xml, or content.xml.xz)
meta="$(mktemp)"; trap 'rm -f "$meta"' EXIT
if [ -f "$site/content.jar" ]; then unzip -p "$site/content.jar" content.xml > "$meta" 2>/dev/null
elif [ -f "$site/content.xml.xz" ]; then xz -dc "$site/content.xml.xz" > "$meta" 2>/dev/null
elif [ -f "$site/content.xml" ]; then cp "$site/content.xml" "$meta"; fi
if grep -Eq "<unit id=.org\.jawata\.eclipse\.feature\.feature\.group. version=.$(printf '%s' "$version" | sed 's/\./\\./g')\." "$meta"; then
    ok "content metadata names org.jawata.eclipse.feature.feature.group at $version"
else bad "content metadata has no org.jawata.eclipse.feature.feature.group at $version"; fi

for banned in org.eclipse.jdt org.eclipse.ltk org.eclipse.swt org.eclipse.jface slf4j org.jawata.jdtpatch org.jawata.resident; do
    if ls "$site/plugins/" 2>/dev/null | grep -q "^${banned}"; then bad "the site ships $banned (Eclipse brings its own)"; fi
done
[ "$fail" = 0 ] && ok "no bundle Eclipse brings itself is shipped"

[ "$fail" = 0 ] && echo "update site $site: all checks hold for version $version" || echo "update site $site: CHECKS FAILED for version $version"
exit "$fail"
