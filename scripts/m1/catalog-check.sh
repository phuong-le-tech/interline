#!/bin/sh
# AC8_catalog: explicit online evidence check, never part of routine offline tests.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
exec python3 "$ROOT/scripts/m1/evidence-check.py" catalog "$@"
