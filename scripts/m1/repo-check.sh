#!/bin/sh
# AC16_hygiene: repository and workflow acceptance assertions.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
exec python3 "$ROOT/scripts/m1/evidence-check.py" hygiene "$@"
