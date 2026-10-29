#!/bin/sh
# The commit-msg hook refuses AI attribution and lets a plain message through.
# It tests scripts/commit-msg, the versioned copy installed into .git/hooks.
set -e
hook="$(dirname "$0")/commit-msg"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf 'plain subject\n' > "$tmp/plain"
printf 'subject\n\nCo-Authored-By: Claude <noreply@anthropic.com>\n' > "$tmp/trailer"
sh "$hook" "$tmp/plain" || { echo "FAIL: a plain message was refused"; exit 1; }
if sh "$hook" "$tmp/trailer" 2>/dev/null; then echo "FAIL: an agent trailer was accepted"; exit 1; fi
# every case of the shared list is refused (the plug-in's check on a draft reads the same list)
printf 'subject\n\nsent from noreply@anthropic.com\n' > "$tmp/address"
printf 'subject\n\nGenerated with [Claude Code]\n' > "$tmp/credit"
printf 'subject\n\nco-authored-by:   ANTHROPIC\n' > "$tmp/shouting"
for case in address credit shouting; do
  if sh "$hook" "$tmp/$case" 2>/dev/null; then echo "FAIL: the case '$case' was accepted"; exit 1; fi
done
echo "commit-msg hook: refuses every case of the shared list"
echo "commit-msg hook: refuses the trailer, accepts a plain message"
# The hook git actually runs is the versioned one (install-hooks.sh keeps them equal).
installed="$(git -C "$(dirname "$0")/.." rev-parse --git-path hooks/commit-msg)"
cmp -s "$hook" "$(dirname "$0")/../$installed" || { echo "FAIL: .git/hooks/commit-msg differs from scripts/commit-msg; run scripts/install-hooks.sh"; exit 1; }
echo "commit-msg hook: the installed hook is the versioned one"
