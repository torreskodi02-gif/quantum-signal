# Shared helpers: find your servers automatically.
QS_LIST="$HOME/.qs-servers"

# Try common usernames until one logs in with your SSH key.
pick_user(){
  local ip="$1" u
  for u in quantum ubuntu opc debian ec2-user root; do
    if ssh -n -o BatchMode=yes -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new "$u@$ip" true 2>/dev/null; then echo "$u@$ip"; return 0; fi
  done
  return 1
}

# 1) servers saved by a previous deploy, 2) servers this phone has logged in to, 3) your typing history
find_targets(){
  if [ -s "$QS_LIST" ]; then cat "$QS_LIST"; return; fi
  local ip found=0
  for ip in $(grep -hoE '^[0-9]{1,3}(\.[0-9]{1,3}){3}' "$HOME/.ssh/known_hosts" 2>/dev/null | awk '!s[$0]++'); do
    pick_user "$ip" && found=1
  done
  [ "$found" = 1 ] && return
  grep -h 'deploy.sh' "$HOME/.bash_history" 2>/dev/null | grep -oE '[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+' | grep -viE 'YOUR|SERVER|example' | awk '!s[$0]++'
}
