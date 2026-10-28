# Sourced by scripts/fetch-engine.sh and scripts/28k-e2e.sh: the one way this repository reads a bundle's symbolic name from its jar.
#
# awk reads to the end on purpose: grep -m1 or head would close the pipe early and, under pipefail, fail the calling script.
bundle_symbolic_name() {  # $1: path of a bundle jar; prints its Bundle-SymbolicName, or nothing
    unzip -p "$1" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' \
        | awk '/^ /{printf "%s", substr($0,2); next} {printf "\n%s", $0} END{print ""}' \
        | awk '/^Bundle-SymbolicName/ && !seen {sub(/^Bundle-SymbolicName: */,""); sub(/;.*/,""); print; seen=1}'
}
