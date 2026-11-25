#!/usr/bin/env bash
# Builds the jpackage app-image "dist/MRemora Agent" with two launchers: MRemora Agent (main) and MRemora Viewer.
# Usage: scripts/build-app-image.sh [app-version]   (default 0.1.0; Windows accepts only x.y.z)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_VERSION="${1:-0.1.0}"
APP_NAME="MRemora Agent"
APP_DIR="$ROOT/mremora-dist/target/app"
DIST="$ROOT/dist"
WORK="$DIST/.work"

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) ICON="$ROOT/packaging/icons/mremora.ico" ;;
    Linux) ICON="$ROOT/packaging/icons/mremora.png" ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

# Git Bash may see JAVA_HOME as C:\...; turn it into /c/... so the -x test below works.
if [[ -n "${JAVA_HOME:-}" ]] && command -v cygpath > /dev/null 2>&1; then
    JAVA_HOME="$(cygpath -u "$JAVA_HOME")"
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jpackage" ]]; then
    JPACKAGE="$JAVA_HOME/bin/jpackage"
elif command -v jpackage > /dev/null 2>&1; then
    JPACKAGE="jpackage"
else
    echo "jpackage not found (set JAVA_HOME to a full JDK 25+ or put jpackage on PATH)" >&2
    exit 1
fi

# Only remove what this script produces; dist/ may hold installers from later steps.
# Also empty the jpackage input so jars of an old dependency version never end up in the image.
rm -rf "${DIST:?}/${APP_NAME:?}" "${WORK:?}" "${APP_DIR:?}"
mkdir -p "$WORK"

(cd "$ROOT" && ./mvnw -B -ntp -q package -DskipTests)

# Exactly one jar matching the glob, printed as a file name.
single_jar() {
    local matches=()
    shopt -s nullglob
    matches=("$APP_DIR"/$1)
    shopt -u nullglob
    if [[ ${#matches[@]} -ne 1 ]]; then
        echo "Expected exactly one $1 in $APP_DIR, found ${#matches[@]}" >&2
        exit 1
    fi
    basename "${matches[0]}"
}

AGENT_JAR="$(single_jar 'mremora-agent-*.jar')"
VIEWER_JAR="$(single_jar 'mremora-viewer-*.jar')"

# jpackage is a native program: on Windows (Git Bash) hand it C:/... paths instead of /c/... ones.
native_path() {
    if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

sed "s/^main-jar=@VIEWER_JAR@/main-jar=$VIEWER_JAR/" "$ROOT/packaging/viewer.properties" > "$WORK/viewer.properties"

"$JPACKAGE" \
    --type app-image \
    --name "$APP_NAME" \
    --input "$(native_path "$APP_DIR")" \
    --main-jar "$AGENT_JAR" \
    --main-class pl.mtech.mremora.agent.AgentMain \
    --java-options --enable-native-access=ALL-UNNAMED \
    --add-launcher "MRemora Viewer=$(native_path "$WORK/viewer.properties")" \
    --icon "$(native_path "$ICON")" \
    --app-version "$APP_VERSION" \
    --description "MRemora Agent" \
    --vendor MRemora \
    --dest "$(native_path "$DIST")"

echo "App image: $DIST/$APP_NAME"
