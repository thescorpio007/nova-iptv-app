#!/bin/bash
# Copies the current code to the PUBLIC repository (free, unlimited builds) — without the signing key,
# its passwords, or the private link-check tool. Usage: tools/publish_public.sh "commit message"
set -e
SRC=$(cd "$(dirname "$0")/.." && pwd)
PUB=${PUB:-/home/claude/nova-iptv-app}
cd "$PUB"
git pull -q --rebase origin main 2>/dev/null || true
# Fresh copy of the tracked files only (minus the private ones).
find "$PUB" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
(cd "$SRC" && git ls-files -z | grep -zvE '^(keystore/|tools/link_check\.py$|\.github/workflows/link-check\.yml$)' \
  | tar --null -T - -cf -) | tar -xf - -C "$PUB"
# Safety: never publish the key or its passwords.
if git -C "$PUB" ls-files --others --cached | grep -qiE '\.jks$|signing\.properties'; then echo "KEY FOUND - stopping"; exit 1; fi
if grep -rIqE 'storePassword\s*=\s*"' "$PUB/app" ; then echo "PASSWORD FOUND - stopping"; exit 1; fi
git add -A
git commit -qm "${1:-Sync}" || { echo "nothing to publish"; exit 0; }
git push -q origin HEAD:main
git log --oneline -1
