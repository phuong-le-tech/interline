#!/bin/sh
# Median and 95th percentile of the STT latency per stream from the core log lines
# `stt_latency_ms=<n> stream=<mic|system>` (same percentile rule as GET /api/v1/meetings/{id}/stats: nearest rank).
# Usage: scripts/m1/latency-from-log.sh LOGFILE...      e.g. ~/Library/Logs/app.interline.desktop/*.log
set -eu
[ "$#" -ge 1 ] || { echo "usage: latency-from-log.sh LOGFILE..." >&2; exit 2; }

for stream in mic system; do
  grep -h "stt_latency_ms=" "$@" | grep "stream=$stream" | sed 's/.*stt_latency_ms=\([0-9.]*\).*/\1/' | sort -n |
    awk -v stream="$stream" '
      { v[NR] = $1 }
      END {
        if (NR == 0) { printf "%s: no finals in the log\n", stream; exit }
        median = v[int((NR * 50 + 99) / 100)]
        p95 = v[int((NR * 95 + 99) / 100)]
        printf "%s: finals=%d median=%s ms p95=%s ms\n", stream, NR, median, p95
      }'
done
