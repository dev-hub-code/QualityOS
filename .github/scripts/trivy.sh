#!/usr/bin/env bash
# Runs the pinned Trivy, failing on a high or critical issue that has a published fix.
set -euo pipefail

mode=$1
shift
exec docker run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$PWD:/repo" -w /repo \
    aquasec/trivy:0.58.2 "$mode" \
    --quiet --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 "$@"
