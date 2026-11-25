#!/usr/bin/env bash
# Builds dist/mremora_<app-version>_<arch>.deb from the Linux app-image "dist/MRemora Agent".
# Usage: scripts/build-deb.sh <app-version>   (x.y.z, e.g. 0.1.42)
#   Run scripts/build-app-image.sh on Linux first. Needs jpackage, dpkg-deb and fakeroot.
#   Install: sudo apt install ./dist/mremora_<app-version>_<arch>.deb
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
IMAGE="$DIST/MRemora Agent"
ICON="$ROOT/packaging/icons/mremora.png"
RESOURCES="$DIST/.work/deb-resources"

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <app-version>" >&2
    exit 1
fi
APP_VERSION="$1"
if [[ ! "$APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: app-version must be x.y.z, got '$APP_VERSION'" >&2
    exit 1
fi

if [[ "$(uname -s)" != Linux ]]; then
    echo "ERROR: .deb can only be built on Linux" >&2
    exit 1
fi

if [[ ! -x "$IMAGE/bin/MRemora Agent" ]]; then
    echo "ERROR: Linux app-image not found: $IMAGE/bin/MRemora Agent - run scripts/build-app-image.sh first" >&2
    exit 1
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jpackage" ]]; then
    JPACKAGE="$JAVA_HOME/bin/jpackage"
elif command -v jpackage > /dev/null 2>&1; then
    JPACKAGE="jpackage"
else
    echo "ERROR: jpackage not found (set JAVA_HOME to a full JDK 25+ or put jpackage on PATH)" >&2
    exit 1
fi

for tool in dpkg-deb fakeroot; do
    command -v "$tool" > /dev/null 2>&1 || {
        echo "ERROR: $tool not found on PATH (sudo apt install dpkg fakeroot)" >&2
        exit 1
    }
done

# Only remove what this script produces; dist/ also holds the app-image.
rm -f "$DIST"/mremora_*.deb
rm -rf "${RESOURCES:?}"
mkdir -p "$RESOURCES"

# Menu icons: with --app-image jpackage (JDK 25/26) ignores --icon and --resource-dir icons for the launchers and
# always writes its default Duke icon to lib/<Launcher_Name>.png. Its post-image hook ("<app name>-post-image.sh"
# in --resource-dir) runs after those files are written and before dpkg-deb, with JpAppImageDir = package root,
# so it overwrites them with our icon. The hook is looked up by app name, which is "MRemora Agent" in JDK 26 (taken
# from the app-image) but may be the --name value in other versions, so it is written under both names (idempotent).
# The icons are also checked inside the built .deb below, in case no hook ran at all.
cat > "$RESOURCES/mremora-post-image.sh" << 'EOF'
#!/bin/bash
set -euo pipefail
for launcher in MRemora_Agent MRemora_Viewer; do
    target="$JpAppImageDir/opt/mremora/lib/$launcher.png"
    if [[ ! -f "$target" ]]; then
        echo "post-image: $target not found (jpackage layout changed?)" >&2
        exit 1
    fi
    cp "$MREMORA_DEB_ICON" "$target"
done
EOF
cp "$RESOURCES/mremora-post-image.sh" "$RESOURCES/MRemora Agent-post-image.sh"
export MREMORA_DEB_ICON="$ICON"

# --name mremora is the package name and the install folder (/opt/mremora). The main launcher's menu entry comes
# from --linux-shortcut; the viewer's from linux-shortcut=true in packaging/viewer.properties (stored in the app-image).
"$JPACKAGE" \
    --type deb \
    --app-image "$IMAGE" \
    --name mremora \
    --linux-package-name mremora \
    --linux-shortcut \
    --linux-menu-group Network \
    --linux-app-category net \
    --app-version "$APP_VERSION" \
    --description "MRemora remote desktop: Agent (share this screen) and Viewer (connect to an agent)" \
    --vendor MRemora \
    --resource-dir "$RESOURCES" \
    --dest "$DIST"

shopt -s nullglob
DEBS=("$DIST"/mremora_*.deb)
shopt -u nullglob
if [[ ${#DEBS[@]} -ne 1 ]]; then
    echo "ERROR: expected exactly one dist/mremora_*.deb, found ${#DEBS[@]}" >&2
    exit 1
fi
for launcher in MRemora_Agent MRemora_Viewer; do
    if ! dpkg-deb --fsys-tarfile "${DEBS[0]}" | tar -xO "./opt/mremora/lib/$launcher.png" | cmp -s - "$ICON"; then
        echo "ERROR: /opt/mremora/lib/$launcher.png in ${DEBS[0]} differs from packaging/icons/mremora.png" \
            "(jpackage did not run the post-image hook?)" >&2
        exit 1
    fi
done
echo "Package: ${DEBS[0]} ($(du -h "${DEBS[0]}" | cut -f1))"
