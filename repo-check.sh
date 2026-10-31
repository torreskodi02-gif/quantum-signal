#!/usr/bin/env bash
# Shows exactly what is on GitHub compared to this phone, and fixes the common problems.
# Usage: bash repo-check.sh          (look)
#        bash repo-check.sh --fix    (also clean up stray files and push)
set -u
cd "$(dirname "$0")"
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok(){ echo "  OK   $*"; }; bad(){ echo "  !!   $*"; PROBLEMS=$((PROBLEMS+1)); }
PROBLEMS=0
for c in git gh curl; do command -v "$c" >/dev/null || { echo "Missing $c. Run: pkg install $c"; exit 1; }; done
export GIT_PAGER=cat

say "Where this phone pushes to"
URL=$(git remote get-url origin 2>/dev/null); echo "  $URL"
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)
[ -n "$REPO" ] && ok "GitHub repo: $REPO" || bad "Can't reach the GitHub repo. Run: gh auth status"
BR=$(git rev-parse --abbrev-ref HEAD 2>/dev/null); echo "  Branch: $BR"
git fetch -q origin 2>/dev/null

say "Is everything pushed?"
read -r AHEAD BEHIND < <(git rev-list --left-right --count "HEAD...origin/$BR" 2>/dev/null || echo "? ?")
[ "$AHEAD" = 0 ] && ok "Nothing waiting to be pushed" || bad "$AHEAD commit(s) are only on this phone. Run: git push"
[ "$BEHIND" = 0 ] && ok "GitHub has nothing newer than this phone" || bad "GitHub has $BEHIND newer commit(s). Run: git pull"
[ -z "$(git status --porcelain)" ] && ok "No unsaved changes" || bad "Unsaved changes (run: bash update.sh or git add -A && git commit && git push)"
echo "  Latest commits:"; git log --oneline -3 | sed 's/^/    /'

say "Junk that does not belong in the repo"
STRAY=$(git ls-files | grep -E '^(quantum-signal/|docs/)|\.apk$|(^|/)data\.json$|\.env$' || true)
if [ -n "$STRAY" ]; then bad "These files should not be on GitHub:"; echo "$STRAY" | head -8 | sed 's/^/    /'
  if [ "${1:-}" = "--fix" ]; then
    echo "$STRAY" | xargs git rm -rq --cached 2>/dev/null; rm -rf quantum-signal docs 2>/dev/null
    git add -A && git commit -qm "Remove stray files" && (git push -q 2>/dev/null || git push -q -u origin HEAD) && echo "  Fixed and pushed."
  else echo "    Run: bash repo-check.sh --fix"; fi
else ok "No stray files"; fi
[ -f www/index.html ] && ok "App files are in www/" || bad "www/index.html is missing from this folder"

say "Does GitHub's copy match this phone?"
LOCALV=$(grep -o '"version":"[^"]*"' www/version.json | cut -d'"' -f4)
REMOTEV=$(gh api "repos/$REPO/contents/www/version.json?ref=$BR" -q .content 2>/dev/null | base64 -d 2>/dev/null | grep -o '"version":"[^"]*"' | cut -d'"' -f4)
echo "  This phone: v$LOCALV    On GitHub: v${REMOTEV:-?}"
[ "$LOCALV" = "$REMOTEV" ] && ok "Same version" || bad "GitHub is not on the same version as this phone"

say "Is the website live?"
OWNER=${REPO%%/*}; NAME=${REPO##*/}
LIVEV=$(curl -fsS -m 10 "https://$OWNER.github.io/$NAME/version.json?t=$(date +%s)" 2>/dev/null | grep -o '"version":"[^"]*"' | cut -d'"' -f4)
echo "  Live site: v${LIVEV:-not reachable}"
[ "$LIVEV" = "$LOCALV" ] && ok "The live app is v$LOCALV" || bad "The live site is not on v$LOCALV yet (give it 2 minutes after a push)"

say "Latest APK builds (newest first)"
gh run list --workflow build-apk.yml --limit 4 --json conclusion,status,headSha,createdAt -q '.[]|"  \(.createdAt[0:16])  \(.headSha[0:7])  \(.conclusion // .status)"' 2>/dev/null
HEAD7=$(git rev-parse --short=7 HEAD)
BUILT=$(gh run list --workflow build-apk.yml --status success --limit 1 --json headSha -q '.[0].headSha' 2>/dev/null | cut -c1-7)
echo "  This phone's newest commit: $HEAD7    Newest finished build is from: ${BUILT:-none}"
[ "$BUILT" = "$HEAD7" ] && ok "The newest APK build matches your newest code" || echo "  (A build runs after every push and takes about 3 minutes.)"
if curl -fsSI -m 10 "https://github.com/$REPO/releases/latest/download/quantum-signal.apk" 2>/dev/null | head -1 | grep -qE ' (200|302)'; then
  ok "Permanent APK link works: https://github.com/$REPO/releases/latest/download/quantum-signal.apk"
else echo "  The permanent APK link will appear after the next build finishes."; fi

say "Summary"
[ "$PROBLEMS" = 0 ] && echo "Everything is in the right place." || echo "$PROBLEMS thing(s) need attention (see !! above)."
