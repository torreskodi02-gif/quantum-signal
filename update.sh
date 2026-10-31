#!/usr/bin/env bash
# One command to update Quantum Signal from GitHub.
# 1) Download the newest quantum-signal.zip from the chat   2) run:  bash update.sh
set -u
cd "$(dirname "$0")"
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
die(){ printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
for c in git gh unzip curl; do command -v "$c" >/dev/null || die "Missing $c. Run: pkg install $c"; done

say "1/4 Looking for the newest zip in Downloads"
DL="${QS_DOWNLOADS:-$HOME/storage/downloads}"
ZIP=""
for z in $(ls -t "$DL"/quantum-signal*.zip 2>/dev/null); do
  unzip -l "$z" 2>/dev/null | grep -q 'www/index.html' && { ZIP="$z"; break; }
done
[ -n "$ZIP" ] || die "No Quantum Signal zip in Downloads. Download the newest one from the chat, then run this again."
echo "Using $(basename "$ZIP")"

say "2/4 Unpacking (your server address is kept)"
OLD_SERVER=$(grep -o '"server":"[^"]*"' www/version.json 2>/dev/null | head -1)
OLD_SERVERS=$(grep -o '"servers":\[[^]]*\]' www/version.json 2>/dev/null | head -1)
unzip -oq "$ZIP" -x update.sh -d . || die "Could not unpack the zip."
V=$(grep -o '"version":"[^"]*"' www/version.json | cut -d'"' -f4)
N=$(grep -o '"notes":"[^"]*"' www/version.json | cut -d'"' -f4)
printf '{"version":"%s","notes":"%s"%s%s}\n' "$V" "$N" "${OLD_SERVER:+,$OLD_SERVER}" "${OLD_SERVERS:+,$OLD_SERVERS}" > www/version.json
echo "Version in this zip: $V"
if ! git diff --quiet -- cloudflare/worker.js 2>/dev/null && [ -s "$HOME/.cf-token" ]; then
  echo "The server code changed, so I'm updating Cloudflare too..."
  bash cloudflare.sh || echo "(Cloudflare update did not finish. You can run: bash cloudflare.sh)"
fi

say "3/4 Sending to GitHub"
git add -A
if git diff --cached --quiet; then
  echo "Nothing changed. GitHub already has version $V."
else
  git commit -qm "Update to v$V"
  (git push -q 2>/dev/null || git push -q -u origin HEAD) || die "Could not push. Check your internet, then run: git push"
  echo "Pushed version $V."
fi

say "4/4 Waiting for GitHub to publish it"
USER_NAME=$(gh api user -q .login 2>/dev/null)
URL="https://${USER_NAME:-YOUR_NAME}.github.io/quantum-signal"
sleep 4
RID=$(gh run list --workflow pages.yml --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null)
[ -n "$RID" ] && gh run watch "$RID" --exit-status >/dev/null 2>&1
LIVE=0
for i in $(seq 1 20); do
  curl -fsS -m 8 "$URL/version.json?t=$(date +%s)" 2>/dev/null | grep -q "\"version\":\"$V\"" && { LIVE=1; break; }
  sleep 3
done
if [ "$LIVE" = 1 ]; then echo "Live: version $V is published."; else echo "Not showing yet. Check: $URL/version.json"; fi

# refresh this script from the zip last, so we never overwrite it while it runs
unzip -p "$ZIP" update.sh > update.sh.new 2>/dev/null && [ -s update.sh.new ] && mv update.sh.new update.sh || rm -f update.sh.new

say "Done"
echo "Now close Quantum Signal completely (swipe it away) and open it again."
echo "Still the old version? Android Settings > Apps > Quantum Signal > Storage > Clear cache."
