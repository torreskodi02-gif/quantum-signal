#!/usr/bin/env bash
# Puts the Quantum Signal server on Cloudflare Workers (free plan).
# No IP address, no firewall, no server to look after, and you get HTTPS.
# Usage: bash cloudflare.sh            (deploy or update)
#        bash cloudflare.sh --forget   (delete the saved token from this phone)
set -u
cd "$(dirname "$0")"
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
die(){ printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
API="https://api.cloudflare.com/client/v4"; NAME="quantum-signal"; TF="$HOME/.cf-token"
[ "${1:-}" = "--forget" ] && { rm -f "$TF"; echo "Saved Cloudflare token removed."; exit 0; }
for c in curl jq git; do
  command -v "$c" >/dev/null || { command -v pkg >/dev/null && pkg install -y "$c" >/dev/null 2>&1; command -v "$c" >/dev/null || die "Missing $c. Run: pkg install $c"; }
done

if [ -s "$TF" ]; then
  TOKEN=$(cat "$TF"); echo "Using your saved Cloudflare token."
else
  cat <<'MSG'

Create a Cloudflare API token (one time only):
  1. In your phone's browser open: dash.cloudflare.com/profile/api-tokens  and sign in.
  2. Tap "Create Token", then "Use template" next to "Edit Cloudflare Workers".
  3. Account Resources: choose your account. Zone Resources: "All zones" is fine.
  4. Tap "Continue to summary", then "Create Token". Copy the token.
Never paste the token into a chat. Paste it only here.
MSG
  read -rsp "Paste the token here (it stays hidden): " TOKEN; echo
fi
TOKEN=$(printf '%s' "$TOKEN" | tr -d ' \r\n')
[ -n "$TOKEN" ] || die "No token entered."
cf(){ curl -sS -m 40 -H "Authorization: Bearer $TOKEN" "$@"; }

say "1/6 Checking your token"
V=$(cf "$API/user/tokens/verify") || die "Could not reach Cloudflare. Check your internet connection."
echo "$V" | jq -e '.result.status=="active"' >/dev/null 2>&1 || { rm -f "$TF"; die "Cloudflare rejected that token. Create a new one and run this again."; }
printf '%s' "$TOKEN" > "$TF"; chmod 600 "$TF"
echo "Token is valid. It is saved only on this phone ($TF)."

say "2/6 Finding your account"
AJ=$(cf "$API/accounts?per_page=10")
ACC=$(echo "$AJ" | jq -r '.result[0].id // empty'); ANAME=$(echo "$AJ" | jq -r '.result[0].name // empty')
[ -n "$ACC" ] || die "This token can't see an account. Create it again with your account chosen under Account Resources."
[ "$(echo "$AJ" | jq '.result|length')" -gt 1 ] && echo "You have several accounts. Using the first one."
echo "Account: $ANAME"

say "3/6 Your free workers.dev address"
SUB=$(cf "$API/accounts/$ACC/workers/subdomain" | jq -r '.result.subdomain // empty')
if [ -z "$SUB" ]; then
  SUB="qs-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  R=$(cf -X PUT "$API/accounts/$ACC/workers/subdomain" -H 'content-type: application/json' --data "{\"subdomain\":\"$SUB\"}")
  echo "$R" | jq -e '.success==true' >/dev/null 2>&1 || die "Could not create your workers.dev address automatically. In the Cloudflare dashboard open Workers & Pages, choose a name when it asks, then run this again."
  echo "Created $SUB.workers.dev"
else
  echo "$SUB.workers.dev"
fi

say "4/6 Uploading the server"
LIST=$(cf "$API/accounts/$ACC/workers/scripts")
if echo "$LIST" | jq -e --arg n "$NAME" '.result[]?|select(.id==$n)' >/dev/null 2>&1; then
  MIG=""; echo "Updating your existing server."
else
  MIG=',"migrations":{"new_tag":"v1","new_sqlite_classes":["Hub"]}'; echo "Creating your server."
fi
printf '{"main_module":"worker.js","compatibility_date":"2025-06-01","bindings":[{"type":"durable_object_namespace","name":"HUB","class_name":"Hub"}]%s}' "$MIG" > ${TMPDIR:-/tmp}/qs-meta.json
UP=$(cf -X PUT "$API/accounts/$ACC/workers/scripts/$NAME" \
  -F "metadata=@${TMPDIR:-/tmp}/qs-meta.json;type=application/json" \
  -F "worker.js=@cloudflare/worker.js;type=application/javascript+module")
echo "$UP" | jq -e '.success==true' >/dev/null 2>&1 || { echo "$UP" | jq -r '.errors[]?|"  "+.message' 2>/dev/null; die "Cloudflare did not accept the upload (reason above)."; }

say "5/6 Switching it on"
cf -X POST "$API/accounts/$ACC/workers/scripts/$NAME/subdomain" -H 'content-type: application/json' --data '{"enabled":true}' | jq -e '.success==true' >/dev/null 2>&1 || echo "(Could not confirm the workers.dev switch, continuing.)"
URL="https://$NAME.$SUB.workers.dev"
echo "Waiting for $URL to come online..."
OK=0
for i in $(seq 1 30); do
  curl -fsS -m 8 "$URL/health" 2>/dev/null | grep -q '"ok":true' && { OK=1; break; }
  sleep 3
done
[ "$OK" = 1 ] && echo "It's live." || echo "Not answering yet. The first time can take a minute. Try: curl $URL/health"

say "6/6 Telling the app where it is"
V=$(grep -o '"version":"[^"]*"' www/version.json | cut -d'"' -f4)
N=$(grep -o '"notes":"[^"]*"' www/version.json | cut -d'"' -f4)
printf '{"version":"%s","notes":"%s","server":"%s","servers":["%s"]}\n' "$V" "$N" "$URL" "$URL" > www/version.json
git add -A && git commit -qm "Use Cloudflare server" 2>/dev/null
(git push -q 2>/dev/null || git push -q -u origin HEAD) && echo "Pushed. Phones pick up the address after GitHub Pages redeploys (1-2 minutes)." || echo "Could not push. Run: git push"

say "Done"
echo "Your server: $URL"
echo "Next: open the app > Settings > Check for updates (or wait a minute). The server appears automatically."
echo "If the app still points at an old server: Settings > Reset server account, then choose your username again."
