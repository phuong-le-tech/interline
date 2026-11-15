#!/bin/sh
# Offline proof: samples the sockets of the Interline processes once a second and fails on any socket that is
# not loopback. Run it while a session is recording (a --test-input session or a real call).
# Usage: scripts/m1/no-network-check.sh SECONDS
# Processes: the shell (interline), the core JVM (interline-core.jar) and the capture agent. Core and agent
# are mandatory, the shell is optional because a headless run has none.
set -eu

seconds=${1:?usage: no-network-check.sh SECONDS}
case "$seconds" in ''|*[!0-9]*) echo "SECONDS must be a positive integer" >&2; exit 2 ;; esac

pids_of() { pgrep -f "$1" 2>/dev/null | tr '\n' ',' | sed 's/,$//' || true; }

shell_pids=$(pids_of '[/]Interline.app/Contents/MacOS/|target/(debug|release)/interline$')
core_pids=$(pids_of 'interline-core.jar')
agent_pids=$(pgrep -x interline-capture-macos 2>/dev/null | tr '\n' ',' | sed 's/,$//' || true)
[ -n "$core_pids" ] || { echo "FAIL: no core process (interline-core.jar) is running" >&2; exit 1; }
[ -n "$agent_pids" ] || { echo "FAIL: no capture agent (interline-capture-macos) is running" >&2; exit 1; }
all=$(printf '%s,%s,%s' "$shell_pids" "$core_pids" "$agent_pids" | sed 's/^,*//; s/,,*/,/g; s/,$//')
echo "sampling pids: shell=[${shell_pids:-none}] core=[$core_pids] agent=[$agent_pids] for ${seconds}s"

# A socket is acceptable when every address in its name is loopback. Wildcard listeners are not.
non_loopback() {
  awk '
    /^p/ { pid = substr($0, 2); next }
    /^n/ {
      name = substr($0, 2); bad = 0
      n = split(name, ends, "->")
      for (i = 1; i <= n; i++) {
        host = ends[i]; sub(/:[^:]*$/, "", host); gsub(/[\[\]]/, "", host)
        if (host != "127.0.0.1" && host != "::1" && host != "localhost") bad = 1
      }
      if (bad) print "pid " pid " " name
    }'
}

violations=0
samples=0
second=0
while [ "$second" -lt "$seconds" ]; do
  found=$(lsof -nP -i -a -p "$all" -F pn 2>/dev/null | non_loopback || true)
  if [ -n "$found" ]; then
    violations=$((violations + 1))
    echo "VIOLATION at ${second}s:"; echo "$found"
  fi
  samples=$((samples + 1))
  second=$((second + 1))
  sleep 1
done

echo "--- nettop byte counters (informational, one sample over the run's last second)"
nettop -P -L 1 -J bytes_in,bytes_out -p "$(echo "$all" | cut -d, -f1)" 2>/dev/null | head -5 || true

if [ "$violations" -gt 0 ]; then
  echo "FAIL: $violations of $samples samples had a non-loopback socket"
  exit 1
fi
echo "PASS: $samples samples, zero non-loopback sockets"
