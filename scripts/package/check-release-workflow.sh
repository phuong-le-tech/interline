#!/bin/sh
# Fails if the secret-holding "release" job runs repository build code or can change the environment of later steps,
# or if the "build" job can see a secret. Usage: check-release-workflow.sh [workflow.yml]
set -eu
wf=${1:-$(dirname "$0")/../../.github/workflows/release.yml}
job() { awk -v j="  $1:" '$0 == j {on=1; next} /^  [a-z]+:$/ {on=0} on' "$wf"; }
release=$(job release); build=$(job build)
[ -n "$release" ] && [ -n "$build" ] || { echo "release.yml needs build and release jobs" >&2; exit 1; }
status=0
for bad in 'pnpm install' 'pnpm ' 'cargo' 'build.sh' 'GITHUB_ENV' 'GITHUB_PATH'; do
  if printf '%s\n' "$release" | grep -nF -- "$bad"; then echo "release job must not contain: $bad" >&2; status=1; fi
done
if printf '%s\n' "$build" | grep -nE 'secrets\.|environment:'; then echo "build job must have no secrets and no environment" >&2; status=1; fi
printf '%s\n' "$release" | grep -q 'environment: release' || { echo "release job needs environment: release" >&2; status=1; }
exit $status
