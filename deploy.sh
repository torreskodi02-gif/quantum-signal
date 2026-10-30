#!/usr/bin/env bash
# Quantum Signal deploy (run in Termux, inside ~/quantum-signal)
# One server  : bash deploy.sh user@host
# A cluster   : bash deploy.sh user@host1 user@host2 user@host3
# Servers in a cluster replicate accounts and messages to each other, and the
# app switches to another server automatically if one goes down.
set -u
cd "$(dirname "$0")"
. ./servers.sh
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
die(){ printf '\n\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

TARGETS=("$@")
if [ ${#TARGETS[@]} -eq 0 ]; then
  mapfile -t TARGETS < <(find_targets)
  if [ ${#TARGETS[@]} -gt 0 ]; then
    echo "I found these servers on my own: ${TARGETS[*]}"
    read -rp "Use them? [Y/n] " A
    case "$A" in n|N) TARGETS=();; esac
  fi
fi
if [ ${#TARGETS[@]} -eq 0 ]; then
  read -rp "Server login(s), separated by spaces (user@ip user@ip ...): " LINE
  read -ra TARGETS <<<"$LINE"
fi
[ ${#TARGETS[@]} -gt 0 ] || die "No server given"
for c in ssh scp tar gh git curl od; do
  command -v "$c" >/dev/null || die "Missing $c. Run: pkg install openssh git gh curl tar coreutils"
done
SSHO="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15"

# Shared secret that lets the servers trust each other (created once, reused on re-runs)
KF="$HOME/.qs-cluster-key"
[ -s "$KF" ] || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$KF"
KEY=$(cat "$KF")

HOSTS=(); URLS=()
for t in "${TARGETS[@]}"; do h="${t#*@}"; HOSTS+=("$h"); URLS+=("http://$h:3000"); done
ALL=$(IFS=,; echo "${URLS[*]}")

say "Latest APK from GitHub"
rm -rf apk
gh run download -n quantum-signal-apk -D apk >/dev/null 2>&1 || die "No APK found. Wait for the Build APK run to finish (gh run list)."
APK=$(find apk -name '*.apk' | head -1)
[ -n "$APK" ] || die "APK file missing in download"
echo "Found $APK"

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
if command -v iptables >/dev/null && $S iptables -S INPUT 2>/dev/null | grep -q REJECT; then
  for pt in 80 3000; do $S iptables -C INPUT -p tcp --dport $pt -j ACCEPT 2>/dev/null || $S iptables -I INPUT -p tcp --dport $pt -j ACCEPT; done
  command -v netfilter-persistent >/dev/null && $S netfilter-persistent save >/dev/null 2>&1
  say "iptables: opened ports 80 and 3000"
fi
sleep 2
if curl -fsS --max-time 5 http://localhost:3000/health >/dev/null; then echo "QS_OK=1"; else echo "QS_OK=0"; fi
REMOTE

n=0
for i in "${!TARGETS[@]}"; do
  T="${TARGETS[$i]}"; n=$((n+1))
  say "Server $n of ${#TARGETS[@]}: $T"

  [ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519 -q
  if ! ssh $SSHO -o BatchMode=yes "$T" true 2>/dev/null; then
    echo "Enter the password for $T once to install your key:"
    cat ~/.ssh/id_ed25519.pub | ssh $SSHO "$T" 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys' \
      || { echo "Could not log in to $T, skipping it."; FAILED="${FAILED:-} $T"; continue; }
  fi

  printf 'PORT=3000\nSELF=%s\nPEERS=%s\nCLUSTER_KEY=%s\n' "${URLS[$i]}" "$ALL" "$KEY" > /tmp/qs-env
  tar czf - server | ssh $SSHO "$T" 'mkdir -p ~/quantum-signal-server && tar xzf - -C ~/quantum-signal-server --strip-components=1' || { echo "Upload to $T failed, skipping."; FAILED="${FAILED:-} $T"; continue; }
  scp -q $SSHO /tmp/qs-env "$T:quantum-signal-server/.env" && ssh $SSHO "$T" 'chmod 600 ~/quantum-signal-server/.env'
  scp -q $SSHO "$APK" "$T:quantum-signal.apk"
  scp -q $SSHO /tmp/qs-setup.sh "$T:qs-setup.sh"
  ssh -t $SSHO "$T" 'bash ~/qs-setup.sh; rm -f ~/qs-setup.sh' | tee "/tmp/qs.out.$n"
  grep -aq 'QS_ERR=node' "/tmp/qs.out.$n" && { echo "Could not install Node.js on $T"; FAILED="${FAILED:-} $T"; }
done

printf '%s\n' "${TARGETS[@]}" > "$QS_LIST"   # remember them for next time
say "Checking every server from your phone"
UPN=0
for u in "${URLS[@]}"; do
  if curl -fsS --max-time 10 "$u/health" 2>/dev/null | grep -q '"ok":true'; then echo "  OK    $u"; UPN=$((UPN+1)); else echo "  DOWN  $u  (check the provider's firewall allows ports 80 and 3000)"; fi
done
[ "$UPN" -eq 0 ] && die "No server answered. Run: bash check.sh   (it finds the problem by itself and tells you the fix)"

say "Telling the app about the servers"
V=$(grep -o '"version":"[^"]*"' www/version.json | cut -d'"' -f4)
N=$(grep -o '"notes":"[^"]*"' www/version.json | cut -d'"' -f4)
LIST=$(printf '"%s",' "${URLS[@]}"); LIST="[${LIST%,}]"
printf '{"version":"%s","notes":"%s","server":"%s","servers":%s,"apk":"%s/quantum-signal.apk"}\n' "$V" "$N" "${URLS[0]}" "$LIST" "http://${HOSTS[0]}" > www/version.json
git add -A && git commit -qm "Set server list" 2>/dev/null
git push -q && echo "Pushed. Phones pick up the server list after GitHub Pages redeploys (1-2 minutes)."

say "Done"
echo "Servers:      ${URLS[*]}"
echo "APK download: http://${HOSTS[0]}/quantum-signal.apk"
[ -n "${FAILED:-}" ] && echo "Skipped/failed:${FAILED}"
echo "Your cluster key is saved in $KF. Keep a copy: you need it to add servers later."
