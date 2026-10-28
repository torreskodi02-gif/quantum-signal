#!/usr/bin/env bash
# Runs the Quantum Signal server on THIS phone (Termux). Usage: bash run-local.sh
# Private mode (only this phone):  HOST=127.0.0.1 bash run-local.sh
cd "$(dirname "$0")"
echo "== Installing Node.js (first run only)"
command -v node >/dev/null || pkg install -y nodejs
command -v ifconfig >/dev/null || pkg install -y net-tools >/dev/null 2>&1
termux-wake-lock 2>/dev/null
APK=$(ls -t app-debug.apk apk/*.apk quantum-signal-apk/*.apk 2>/dev/null | head -1)
[ -n "$APK" ] && cp "$APK" server/quantum-signal.apk && echo "APK ready: $APK"
pkill -f "node server.js" 2>/dev/null
cd server
HOST="${HOST:-0.0.0.0}" nohup node server.js > server.log 2>&1 &
sleep 2
if curl -fsS http://127.0.0.1:3000/health >/dev/null; then
  IP=$(ifconfig wlan0 2>/dev/null | awk '/inet /{print $2}')
  echo; echo "== Server is running"
  echo "In the app (this phone): Settings > Server address > http://127.0.0.1:3000"
  if [ "${HOST:-0.0.0.0}" = "0.0.0.0" ] && [ -n "$IP" ]; then
    echo "Other phones on the same Wi-Fi: http://$IP:3000"
    echo "APK download for them:         http://$IP:3000/quantum-signal.apk"
  fi
  echo "Logs: tail -f ~/quantum-signal/server/server.log"
else
  echo "Server did not start. Details:"; cat server.log
fi
