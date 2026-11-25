#!/usr/bin/env bash
# Builds dist/MRemora-setup-<version>.exe (NSIS, per-user) from the Windows app-image "dist/MRemora Agent".
# Usage: scripts/build-installer.sh <YYYY.MM.DD.N>
#   Run scripts/build-app-image.sh on Windows first (the installer packs the Windows launchers).
#   STAGE_DIR=<dir>       use another app-image dir instead of dist/MRemora Agent
#   ALLOW_FAKE_STAGE=1    skip the "is it a Windows app-image" check (makensis syntax test on Linux)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
STAGE="${STAGE_DIR:-$DIST/MRemora Agent}"
ICON="$ROOT/packaging/icons/mremora.ico"
NSI="$ROOT/installer/mremora.nsi"

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <YYYY.MM.DD.N>" >&2
    exit 1
fi
VERSION="$1"
# VIProductVersion needs exactly four numeric parts, each at most 65535.
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: version must be X.X.X.X (YYYY.MM.DD.N), got '$VERSION'" >&2
    exit 1
fi
IFS=. read -r -a PARTS <<< "$VERSION"
for part in "${PARTS[@]}"; do
    if (( 10#$part > 65535 )); then
        echo "ERROR: every version part must be <= 65535, got '$VERSION'" >&2
        exit 1
    fi
done

command -v makensis > /dev/null 2>&1 || {
    echo "ERROR: makensis not found on PATH (Linux: sudo apt install nsis; Windows: choco install nsis)" >&2
    exit 1
}

if [[ ! -d "$STAGE" ]]; then
    echo "ERROR: app-image not found: $STAGE - build the app-image on Windows first (scripts/build-app-image.sh)" >&2
    exit 1
fi
if [[ "${ALLOW_FAKE_STAGE:-0}" != 1 ]]; then
    for f in "MRemora Agent.exe" "MRemora Viewer.exe" "app" "runtime"; do
        if [[ ! -e "$STAGE/$f" ]]; then
            echo "ERROR: $STAGE has no '$f' - not a Windows app-image; build the app-image on Windows first (scripts/build-app-image.sh)" >&2
            exit 1
        fi
    done
    # mremora.nsi deletes exactly these root entries on update/uninstall; anything else would be left behind.
    shopt -s dotglob nullglob
    for entry in "$STAGE"/*; do
        case "$(basename "$entry")" in
            "MRemora Agent.exe" | "MRemora Viewer.exe" | app | runtime) ;;
            "MRemora Agent.ico" | "MRemora Viewer.ico") ;; # optional, written by jpackage on Windows
            *.dll) # optional MSVC runtime (vcruntime140.dll, msvcp140.dll...); the .nsi deletes $INSTDIR\*.dll
                if [[ ! -f "$entry" ]]; then
                    echo "ERROR: '$(basename "$entry")' in app-image root is not a file" >&2
                    exit 1
                fi
                ;;
            *)
                echo "ERROR: unexpected entry in app-image root: '$(basename "$entry")' - allowed: the two launcher .exe/.ico, app, runtime, *.dll; add it to REMOVE_PROGRAM_FILES in installer/mremora.nsi and to this list" >&2
                exit 1
                ;;
        esac
    done
    shopt -u dotglob nullglob
fi

OUTFILE="$DIST/MRemora-setup-$VERSION.exe"
mkdir -p "$DIST"
rm -f "$OUTFILE"

# makensis is a native program on Windows (Git Bash): hand it C:\... paths.
native_path() {
    if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}

# -WX: any makensis warning fails the build.
makensis -V2 -WX -INPUTCHARSET UTF8 \
    "-DVERSION=$VERSION" \
    "-DSTAGE=$(native_path "$STAGE")" \
    "-DOUTFILE=$(native_path "$OUTFILE")" \
    "-DICON=$(native_path "$ICON")" \
    "$(native_path "$NSI")"

echo "Installer: $OUTFILE ($(du -h "$OUTFILE" | cut -f1))"
