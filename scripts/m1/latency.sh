#!/bin/sh
# AC11_latency: check actual ten-minute native-run evidence; never synthesize measurements.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
exec python3 "$ROOT/scripts/m1/evidence-check.py" latency "$@"
