#!/usr/bin/env bash
# Finds your servers by itself, checks them, and fixes or explains what's wrong.
# Usage: bash check.sh            (uses the servers it finds automatically)
#        bash check.sh user@host  (check one specific server)
cd "$(dirname "$0")"
. ./servers.sh
say(){ printf '\n\033[1m== %s\033[0m\n' "$*"; }
TARGETS=("$@")
if [ ${#TARGETS[@]} -eq 0 ]; then mapfile -t TARGETS < <(find_targets); fi
[ ${#TARGETS[@]} -gt 0 ] || { echo "I couldn't find any servers. Run: bash deploy.sh user@ip"; exit 1; }
SSHO="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o BatchMode=yes"
GOOD=0
for T in "${TARGETS[@]}"; do
  H="${T#*@}"
  say "Checking $T"
  if curl -fsS -m 8 "http://$H:3000/health" 2>/dev/null | grep -q '"ok":true'; then
    echo "OK: reachable from your phone at http://$H:3000"; GOOD=$((GOOD+1)); continue
  fi
  echo "Not reachable from your phone. Looking inside the server..."
  OUT=$(ssh $SSHO "$T" 'bash -s' 2>/dev/null <<'REMOTE'
echo "SVC=$(systemctl is-active quantum-signal 2>/dev/null || echo none)"
if curl -fsS -m 4 http://localhost:3000/health >/dev/null 2>&1; then echo LOCAL=ok; else echo LOCAL=bad; fi
PROV=other
curl -fsS -m 2 -H 'Metadata-Flavor: Google' http://metadata.google.internal/computeMetadata/v1/instance/id >/dev/null 2>&1 && PROV=gcp
curl -fsS -m 2 -H 'Authorization: Bearer Oracle' http://169.254.169.254/opc/v2/instance/ >/dev/null 2>&1 && PROV=oracle
echo "PROV=$PROV"
REMOTE
)
  if ! echo "$OUT" | grep -q '^SVC='; then
    echo "I can't log in to $T."
    echo "Check the address is right and the server is running (Google Cloud: Compute Engine > VM instances)."
    continue
  fi
  SVC=$(echo "$OUT" | sed -n 's/^SVC=//p'); LOCAL=$(echo "$OUT" | sed -n 's/^LOCAL=//p'); PROV=$(echo "$OUT" | sed -n 's/^PROV=//p')
  if [ "$LOCAL" != ok ]; then
    echo "The server program isn't answering (state: $SVC). Restarting it..."
    ssh -n $SSHO "$T" 'sudo -n systemctl restart quantum-signal 2>/dev/null || (cd ~/quantum-signal-server && nohup node server.js >> server.log 2>&1 &); sleep 3; curl -fsS -m 4 http://localhost:3000/health >/dev/null && echo RESTART_OK' 2>/dev/null | grep -q RESTART_OK \
      && { echo "Restarted. Checking again..."; curl -fsS -m 8 "http://$H:3000/health" 2>/dev/null | grep -q '"ok":true' && { echo "OK: fixed. Reachable at http://$H:3000"; GOOD=$((GOOD+1)); continue; } ; LOCAL=ok; } \
      || { echo "It would not start. Last log lines:"; ssh -n $SSHO "$T" 'sudo -n journalctl -u quantum-signal -n 15 --no-pager 2>/dev/null || tail -15 ~/quantum-signal-server/server.log' 2>/dev/null; continue; }
  fi
  echo "The server is running fine, but its network firewall blocks port 3000 from outside."
  case "$PROV" in
    gcp) echo "This is Google Cloud. Console > VPC network > Firewall > Create firewall rule:"
         echo "  Direction: Ingress | Action: Allow | Targets: All instances in the network"
         echo "  Source IPv4 ranges: 0.0.0.0/0 | Protocols and ports: TCP 3000  (then Create)";;
    oracle) echo "This is Oracle Cloud. Console > Networking > Virtual Cloud Networks > your network > Security Lists > Add Ingress Rule:"
         echo "  Source CIDR: 0.0.0.0/0 | IP Protocol: TCP | Destination port: 3000  (add port 80 the same way)";;
    *) echo "Open TCP port 3000 (and 80) for incoming traffic in your hosting provider's firewall or security group.";;
  esac
  echo "After saving the rule, run: bash check.sh"
done
say "Summary"
echo "$GOOD of ${#TARGETS[@]} server(s) reachable."
[ "$GOOD" -gt 0 ] && echo "Next: open the app, then Settings > Servers > Test all now."
