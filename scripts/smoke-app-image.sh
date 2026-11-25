#!/usr/bin/env bash
# Smoke test of the app-image built by scripts/build-app-image.sh: the agent launcher must start the bundled
# runtime and reach argument parsing, which rejects "--port x" with exit code 2.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="$ROOT/dist/MRemora Agent"

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) LAUNCHER="$IMAGE/MRemora Agent.exe" ;;
    Linux) LAUNCHER="$IMAGE/bin/MRemora Agent" ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

if [[ ! -x "$LAUNCHER" ]]; then
    echo "Agent launcher not found: $LAUNCHER (run scripts/build-app-image.sh first)" >&2
    exit 1
fi

CMD=("$LAUNCHER" --port x)
if [[ "$(uname -s)" == Linux && -z "${DISPLAY:-}" ]]; then
    if ! command -v xvfb-run > /dev/null 2>&1; then
        echo "No DISPLAY and xvfb-run is missing (install the xvfb package)" >&2
        exit 1
    fi
    CMD=(xvfb-run -a "${CMD[@]}")
fi

# Direct invocation (via timeout): bash waits for the process (also for the GUI-subsystem exe on Windows) and gets its exit code.
set +e
MREMORA_NO_DIALOGS=1 timeout 120 "${CMD[@]}"
CODE=$?
set -e

if [[ $CODE -eq 124 ]]; then
    echo "Smoke test FAILED: '$LAUNCHER --port x' did not exit within 120 s (killed)" >&2
    exit 1
fi

if [[ $CODE -ne 2 ]]; then
    echo "Smoke test FAILED: expected exit code 2 from '$LAUNCHER --port x', got $CODE" >&2
    exit 1
fi
echo "Smoke test OK: agent launcher exited with 2 on invalid arguments"
