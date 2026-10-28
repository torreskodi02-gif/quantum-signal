#!/usr/bin/env bash
# Quantum Signal one-command deploy (run in Termux, inside ~/quantum-signal)
# Usage: bash deploy.sh user@YOUR_SERVER_IP
set -u
cd "$(dirname "$0")"
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
die(){ printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

T="${1:-}"
[ -z "$T" ] && read -rp "Server login (user@ip-or-domain): " T
[ -z "$T" ] && die "No server given"
HOST="${T#*@}"
for c in ssh scp tar gh git curl; do
  command -v "$c" >/dev/null || die "Missing $c. Run: pkg install openssh git gh curl tar"
done
SSHO="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15"

say "1/6 SSH key"
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519 -q
if ! ssh $SSHO -o BatchMode=yes "$T" true 2>/dev/null; then
  echo "Enter the server password once to install your key:"
  cat ~/.ssh/id_ed25519.pub | ssh $SSHO "$T" 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys' \
    || die "Could not log in to $T (check the address, username and that SSH is open)"
fi
echo "Logged in to $T"

say "2/6 Latest APK from GitHub"
rm -rf apk
gh run download -n quantum-signal-apk -D apk >/dev/null 2>&1 || die "No APK found. Wait for the Build APK run to finish (gh run list)."
APK=$(find apk -name '*.apk' | head -1)
[ -n "$APK" ] || die "APK file missing in download"
echo "Found $APK"

say "3/6 Uploading server and APK"
tar czf - server | ssh $SSHO "$T" 'mkdir -p ~/quantum-signal-server && tar xzf - -C ~/quantum-signal-server --strip-components=1' || die "Upload of server failed"
scp -q $SSHO "$APK" "$T:quantum-signal.apk" || die "Upload of APK failed"

cat > /tmp/qs-setup.sh <<'REMOTE'
set -u
S=""; [ "$(id -u)" -ne 0 ] && S="sudo"
say(){ echo "-- $*"; }
sh_root(){ if [ -n "$S" ]; then sudo -E bash -; else bash -; fi; }
PM=""
command -v apt-get >/dev/null && PM=apt
[ -z "$PM" ] && command -v dnf >/dev/null && PM=dnf
[ -z "$PM" ] && command -v yum >/dev/null && PM=yum
inst(){
  case $PM in
    apt) $S apt-get install -y "$@";;
    dnf) $S dnf install -y "$@";;
    yum) $S yum install -y "$@";;
    *) echo "Unsupported system: install $* yourself"; return 1;;
  esac
}
say "System: ${PM:-unknown}, user: $(id -un)"

need=1
if command -v node >/dev/null; then
  [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 18 ] && need=0
fi
if [ $need = 1 ]; then
  say "Installing Node.js 20"
  if [ "$PM" = apt ]; then
    $S apt-get update -y; inst curl ca-certificates
    curl -fsSL https://deb.nodesource.com/setup_20.x | sh_root; inst nodejs
  else
    inst curl; curl -fsSL https://rpm.nodesource.com/setup_20.x | sh_root; inst nodejs
  fi
fi
command -v node >/dev/null || { echo "QS_ERR=node"; exit 1; }
say "Node $(node -v)"

if ss -ltn 2>/dev/null | grep -q ':80 '; then
  say "Web server already running on port 80"
else
  say "Installing nginx"
  inst nginx && $S systemctl enable --now nginx
fi
WEB=""
for d in /var/www/html /usr/share/nginx/html /srv/www/htdocs; do [ -d "$d" ] && { WEB=$d; break; }; done
[ -z "$WEB" ] && { $S mkdir -p /var/www/html; WEB=/var/www/html; }
$S cp ~/quantum-signal.apk "$WEB/quantum-signal.apk" && $S chmod 644 "$WEB/quantum-signal.apk"
say "APK published in $WEB"

cd ~/quantum-signal-server || exit 1
if command -v systemctl >/dev/null && [ -d /run/systemd/system ]; then
  say "Installing background service (starts on boot, restarts if it crashes)"
  $S tee /etc/systemd/system/quantum-signal.service >/dev/null <<UNIT
[Unit]
Description=Quantum Signal relay
After=network.target
[Service]
User=$(id -un)
WorkingDirectory=$HOME/quantum-signal-server
ExecStart=$(command -v node) server.js
Restart=always
[Install]
WantedBy=multi-user.target
UNIT
  $S systemctl daemon-reload && $S systemctl enable quantum-signal >/dev/null 2>&1 && $S systemctl restart quantum-signal
else
  say "No systemd: starting with nohup"
  pkill -f "node server.js" 2>/dev/null
  nohup node server.js > server.log 2>&1 &
fi

if command -v ufw >/dev/null && $S ufw status 2>/dev/null | grep -q "Status: active"; then
  $S ufw allow 80/tcp >/dev/null; $S ufw allow 3000/tcp >/dev/null; say "ufw: opened ports 80 and 3000"
fi
if command -v firewall-cmd >/dev/null && $S firewall-cmd --state 2>/dev/null | grep -q running; then
  $S firewall-cmd --permanent --add-port=80/tcp --add-port=3000/tcp >/dev/null; $S firewall-cmd --reload >/dev/null; say "firewalld: opened ports 80 and 3000"
fi

sleep 2
IP=$(curl -fsS --max-time 8 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')
echo "QS_IP=$IP"
if curl -fsS --max-time 5 http://localhost:3000/health >/dev/null; then echo "QS_OK=1"; else echo "QS_OK=0"; fi
REMOTE

say "4/6 Detecting the server and setting everything up (may ask for the sudo password)"
scp -q $SSHO /tmp/qs-setup.sh "$T:qs-setup.sh" || die "Could not upload setup script"
ssh -t $SSHO "$T" 'bash ~/qs-setup.sh; rm -f ~/qs-setup.sh' | tee /tmp/qs.out
IP=$(grep -a '^QS_IP=' /tmp/qs.out | tail -1 | tr -d '\r' | cut -d= -f2)
[ -z "$IP" ] && IP="$HOST"
grep -aq 'QS_ERR=node' /tmp/qs.out && die "Could not install Node.js on the server"
grep -aq 'QS_OK=1' /tmp/qs.out || echo "Warning: the server did not answer locally. On the server run: sudo journalctl -u quantum-signal -n 30"

say "5/6 Checking from your phone"
H1=bad; H2=bad
curl -fsS --max-time 10 "http://$IP:3000/health" >/dev/null 2>&1 && H1=ok
curl -fsSI --max-time 10 "http://$IP/quantum-signal.apk" 2>/dev/null | head -1 | grep -q 200 && H2=ok
echo "Server (port 3000): $H1"
echo "APK download (port 80): $H2"
[ "$H1" = bad ] || [ "$H2" = bad ] && echo "If something says bad, your hosting provider's firewall or security group may still block ports 80 and 3000. Open them in the provider's dashboard."

say "6/6 Telling the app where the server is"
V=$(grep -o '"version":"[^"]*"' www/version.json | cut -d'"' -f4)
N=$(grep -o '"notes":"[^"]*"' www/version.json | cut -d'"' -f4)
printf '{"version":"%s","notes":"%s","server":"http://%s:3000","apk":"http://%s/quantum-signal.apk"}\n' "$V" "$N" "$IP" "$IP" > www/version.json
git add -A && git commit -qm "Set server address" 2>/dev/null
git push -q && echo "Pushed. The app picks up the server address after GitHub Pages redeploys (1-2 minutes)."

say "Done"
echo "APK download:  http://$IP/quantum-signal.apk"
echo "Server health: http://$IP:3000/health"
