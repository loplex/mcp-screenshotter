#!/usr/bin/env bash
# Runs every e2e/test_*.py scenario in sequence and reports which passed/failed.
#
# Each scenario starts its own MCP server process against the packaged jar
# (see README.md's "Run E2E Tests" section for how to build it), so they run
# one at a time here rather than in parallel - they'd otherwise fight over the
# same sandbox display backend.
set -uo pipefail

# cd to the repo root (this script's parent dir), not to e2e/ itself: the test
# scripts hardcode their paths (jar, sample_app.py) relative to the repo root,
# matching how README.md documents running them individually
# (`python3 e2e/test_gui.py` from the root).
cd "$(dirname "${BASH_SOURCE[0]}")/.."

failures=()
for test_file in e2e/test_*.py; do
    name="$(basename "$test_file")"
    echo "=============================================="
    echo "Running $name"
    echo "=============================================="
    if ! python3 "$test_file"; then
        failures+=("$name")
    fi
    echo
done

if [ "${#failures[@]}" -ne 0 ]; then
    echo "FAILED: ${failures[*]}"
    exit 1
fi

echo "All e2e tests passed."
