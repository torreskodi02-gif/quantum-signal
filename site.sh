#!/usr/bin/env bash
# Hosts a download page for the NEWEST Quantum Signal APK from this phone, at your phone's IP address.
# Usage: bash site.sh         (fetch the newest APK from GitHub, start the page)
#        bash site.sh stop    (stop the page)
set -u
cd "$(dirname "$0")"
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
die(){ printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
SITE="$HOME/.qs-site"; PORT="${QS_PORT:-8080}"; mkdir -p "$SITE"
if [ "${1:-}" = stop ]; then
  [ -s "$SITE/pid" ] && kill "$(cat "$SITE/pid")" 2>/dev/null; rm -f "$SITE/pid"; echo "Download page stopped."; exit 0
fi
for c in node curl gh; do command -v "$c" >/dev/null || { command -v pkg >/dev/null && pkg install -y "$([ "$c" = node ] && echo nodejs || echo "$c")" >/dev/null 2>&1; command -v "$c" >/dev/null || die "Missing $c. Run: pkg install $c"; }; done
command -v ifconfig >/dev/null || { command -v pkg >/dev/null && pkg install -y net-tools >/dev/null 2>&1; }

say "1/4 Getting the newest APK from GitHub"
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)
[ -n "$REPO" ] || die "Can't tell which GitHub repo this is. Run this inside ~/quantum-signal and make sure 'gh auth status' works."
NEW="$SITE/quantum-signal.apk.new"; rm -f "$NEW"
SRC=""
if curl -fsSL -m 180 -o "$NEW" "https://github.com/$REPO/releases/latest/download/quantum-signal.apk" 2>/dev/null && [ "$(wc -c < "$NEW")" -gt 1000000 ]; then
  SRC="the 'latest' release"
else
  echo "No 'latest' release yet, taking the newest finished build instead."
  RID=$(gh run list --workflow build-apk.yml --status success --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null)
  [ -n "$RID" ] || die "No finished APK build found. Check: gh run list --workflow build-apk.yml"
  rm -rf "$SITE/dl"
  gh run download "$RID" -n quantum-signal-apk -D "$SITE/dl" >/dev/null 2>&1 || die "Could not download the APK from build $RID."
  F=$(find "$SITE/dl" -name '*.apk' | head -1); [ -n "$F" ] || die "That build had no APK file."
  mv "$F" "$NEW"; SRC="build #$RID"
fi
mv -f "$NEW" "$SITE/quantum-signal.apk"
SIZE=$(du -h "$SITE/quantum-signal.apk" | cut -f1); SUM=$(sha256sum "$SITE/quantum-signal.apk" | cut -c1-16)
SHA=$(gh run list --workflow build-apk.yml --status success --limit 1 --json headSha -q '.[0].headSha' 2>/dev/null | cut -c1-7)
V=$(grep -o '"version":"[^"]*"' www/version.json 2>/dev/null | cut -d'"' -f4)
echo "Got it from $SRC ($SIZE)"

say "2/4 Building the page"
cat > "$SITE/index.html" <<HTML
<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Quantum Signal download</title>
<style>:root{color-scheme:light dark}body{font:16px/1.5 system-ui,sans-serif;max-width:30rem;margin:0 auto;padding:2rem 1.2rem}
h1{margin:.2em 0}.card{border:1px solid #8884;border-radius:16px;padding:1rem 1.2rem;margin:1rem 0}
a.btn{display:block;text-align:center;background:#3a76f0;color:#fff;text-decoration:none;font-weight:600;padding:1rem;border-radius:14px;margin:1rem 0}
small,li{color:#888}code{font-size:.85em}</style></head><body>
<h1>Quantum Signal</h1><p>Private messenger for Android.</p>
<a class="btn" href="quantum-signal.apk" download>Download the app ($SIZE)</a>
<div class="card"><b>This build</b><br><small>App version $V<br>Built from commit ${SHA:-unknown}<br>Page updated $(date '+%Y-%m-%d %H:%M')<br>Checksum <code>$SUM</code></small></div>
<div class="card"><b>Installing</b><ol><li>Open the downloaded file.</li><li>If Android asks, allow installs from this browser.</li><li>If it says "App not installed", uninstall any older Quantum Signal first, then try again.</li></ol></div>
</body></html>
HTML
echo "Page written."

say "3/4 Starting the page"
[ -s "$SITE/pid" ] && kill "$(cat "$SITE/pid")" 2>/dev/null; sleep 1
cp site/serve.js "$SITE/serve.js"
PORT="$PORT" nohup node "$SITE/serve.js" "$SITE" > "$SITE/log" 2>&1 &
echo $! > "$SITE/pid"
command -v termux-wake-lock >/dev/null && termux-wake-lock 2>/dev/null
sleep 1
curl -fsS -m 5 "http://127.0.0.1:$PORT/" >/dev/null 2>&1 || { echo "The page did not start. Log:"; cat "$SITE/log"; die "Could not start the web page."; }

say "4/4 Your addresses"
phone_ip(){
  local ip
  ip=$(ifconfig wlan0 2>/dev/null | awk '/inet /{print $2}' | head -1); [ -n "$ip" ] && { echo "$ip"; return; }
  ip=$(ip -4 addr show wlan0 2>/dev/null | awk '/inet /{sub("/.*","",$2);print $2}' | head -1); [ -n "$ip" ] && { echo "$ip"; return; }
  ip=$(ifconfig 2>/dev/null | awk '/inet / && $2!="127.0.0.1"{print $2}' | head -1); [ -n "$ip" ] && { echo "$ip"; return; }
  command -v termux-wifi-connectioninfo >/dev/null && termux-wifi-connectioninfo 2>/dev/null | grep -o '"ip": *"[^"]*"' | cut -d'"' -f4
}
IP=$(phone_ip)
echo "The download page is running."
echo "  On this phone:                    http://127.0.0.1:$PORT"
if [ -n "$IP" ]; then
  echo "  Other devices on the same Wi-Fi:  http://$IP:$PORT"
  command -v qrencode >/dev/null && qrencode -t ANSIUTF8 "http://$IP:$PORT"
else
  echo "  (Couldn't read your Wi-Fi address. Check you're on Wi-Fi, then run this again.)"
fi
echo
echo "Run 'bash site.sh' again any time to refresh it with the newest build. Stop it with: bash site.sh stop"
