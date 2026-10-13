#!/bin/sh
# Build a self-contained app for the architecture of this macOS Java 17 installation.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_DIR"
if [ "$(uname -s)" != Darwin ]; then
    printf '%s\n' 'macOS packaging requires a macOS host with Java 17 jpackage.' >&2
    exit 1
fi
APP_VERSION=${ACCESS_APP_VERSION:-1.0.1}
PACKAGE_TYPE=${1:-app-image}
case "$PACKAGE_TYPE" in app-image|dmg) ;; *) printf '%s\n' 'Usage: scripts/package-macos.sh [app-image|dmg]' >&2; exit 1 ;; esac
JAVA_HOME_PACKAGE=${ACCESS_JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}
if [ ! -x "$JAVA_HOME_PACKAGE/bin/jpackage" ]; then JAVA_HOME_PACKAGE=$(/usr/libexec/java_home -v 17); fi
if [ ! -x "$JAVA_HOME_PACKAGE/bin/jpackage" ]; then
    printf '%s\n' 'Java 17 with jpackage was not found. Set ACCESS_JAVA_HOME to a local Java 17 JDK.' >&2
    exit 1
fi
if [ "${ACCESS_SKIP_BUILD:-0}" != 1 ]; then ./scripts/build.sh; fi
if [ ! -f build/access-for-mac.jar ]; then printf '%s\n' 'Run scripts/build.sh first.' >&2; exit 1; fi
mkdir -p build/mac build/package-work
STAGING_DIR=$(mktemp -d "$PROJECT_DIR/build/package-work/input.XXXXXX")
PACKAGE_OUTPUT=$(mktemp -d "${TMPDIR:-/tmp}/access-for-mac-package.XXXXXX")
trap 'rm -rf "$STAGING_DIR" "$PACKAGE_OUTPUT"' EXIT HUP INT TERM
mkdir -p "$STAGING_DIR/lib" "$STAGING_DIR/licenses"
cp lib/*.jar "$STAGING_DIR/lib/"
if [ -d licenses ]; then cp -R licenses/. "$STAGING_DIR/licenses/"; fi
if [ -f README.md ]; then cp README.md "$STAGING_DIR/README.md"; fi
for notice in NOTICE LEGAL.md; do
    if [ -f "$notice" ]; then cp "$notice" "$STAGING_DIR/$notice"; fi
done
# Resolve library jars through the manifest in the packaged jar, rather than the user's Java setup.
MANIFEST_FILE="$STAGING_DIR/classpath.mf"
printf 'Class-Path:' > "$MANIFEST_FILE"
for dependency in lib/*.jar; do printf ' %s' "$dependency" >> "$MANIFEST_FILE"; done
printf '\n\n' >> "$MANIFEST_FILE"
"$JAVA_HOME_PACKAGE/bin/jar" --create --file "$STAGING_DIR/access-for-mac.jar" --main-class org.accessformac.ui.AccessForMacApp --manifest "$MANIFEST_FILE" -C build/classes .
rm "$MANIFEST_FILE"
ASSOCIATION_ACCDB="$PROJECT_DIR/build/package-work/accdb.properties"
ASSOCIATION_MDB="$PROJECT_DIR/build/package-work/mdb.properties"
printf 'extension=accdb\nmime-type=application/msaccess\ndescription=Access ACCDB Database\n' > "$ASSOCIATION_ACCDB"
printf 'extension=mdb\nmime-type=application/x-msaccess\ndescription=Access MDB Database\n' > "$ASSOCIATION_MDB"
APP_IMAGE="$PROJECT_DIR/build/mac/Lattice.app"
# This path is exclusively the generated app, not a checkout or user database.
if [ -d "$APP_IMAGE" ]; then rm -rf "$APP_IMAGE"; fi
"$JAVA_HOME_PACKAGE/bin/java" -cp build/classes org.accessformac.ui.AppIcon "$PACKAGE_OUTPUT/Lattice.iconset"
/usr/bin/iconutil -c icns -o "$PACKAGE_OUTPUT/Lattice.icns" "$PACKAGE_OUTPUT/Lattice.iconset"
"$JAVA_HOME_PACKAGE/bin/jpackage" \
    --type app-image \
    --name Lattice \
    --app-version "$APP_VERSION" \
    --vendor 'Lattice contributors' \
    --description 'Independent Access-compatible database and SQL workspace' \
    --input "$STAGING_DIR" \
    --main-jar access-for-mac.jar \
    --main-class org.accessformac.ui.AccessForMacApp \
    --icon "$PACKAGE_OUTPUT/Lattice.icns" \
    --dest "$PACKAGE_OUTPUT" \
    --add-modules java.desktop,java.sql,java.naming,java.logging,java.management,jdk.unsupported \
    --mac-package-identifier org.accessformac.desktop \
    --mac-package-name Lattice \
    --mac-app-category productivity \
    --file-associations "$ASSOCIATION_ACCDB" \
    --file-associations "$ASSOCIATION_MDB" \
    --java-options '-Dapple.laf.useScreenMenuBar=true'
/usr/bin/codesign --verify --deep --strict "$PACKAGE_OUTPUT/Lattice.app"
"$PACKAGE_OUTPUT/Lattice.app/Contents/MacOS/Lattice" --verify-install
ARCHIVE_NAME="Lattice-$APP_VERSION-$(uname -m).zip"
/usr/bin/ditto -c -k --keepParent --norsrc --noextattr "$PACKAGE_OUTPUT/Lattice.app" "$PACKAGE_OUTPUT/$ARCHIVE_NAME"
mkdir -p "$PACKAGE_OUTPUT/archive-check"
/usr/bin/ditto -x -k "$PACKAGE_OUTPUT/$ARCHIVE_NAME" "$PACKAGE_OUTPUT/archive-check"
/usr/bin/codesign --verify --deep --strict "$PACKAGE_OUTPUT/archive-check/Lattice.app"
cp "$PACKAGE_OUTPUT/$ARCHIVE_NAME" "$PROJECT_DIR/build/mac/$ARCHIVE_NAME"
# File-provider folders may attach FinderInfo while an app is being created, causing codesign
# to fail. Finish jpackage's ad-hoc signing in system temp, then copy the finished bundle.
/usr/bin/ditto --norsrc --noextattr "$PACKAGE_OUTPUT/Lattice.app" "$APP_IMAGE"
# Finder may add this folder attribute after copying into Documents. Remove only the
# two metadata attributes disallowed by codesign, from this generated output bundle.
/usr/bin/xattr -dr com.apple.ResourceFork "$APP_IMAGE" 2>/dev/null || true
/usr/bin/xattr -dr com.apple.FinderInfo "$APP_IMAGE" 2>/dev/null || true
printf 'Built %s (%s; bundled Java 17 runtime)\n' "$APP_IMAGE" "$(uname -m)"
printf 'Verified ad-hoc signed archive: %s/build/mac/%s\n' "$PROJECT_DIR" "$ARCHIVE_NAME"
if [ "$PACKAGE_TYPE" = dmg ]; then
    DMG="$PROJECT_DIR/build/mac/Lattice-$APP_VERSION.dmg"
    if [ -f "$DMG" ]; then rm "$DMG"; fi
    "$JAVA_HOME_PACKAGE/bin/jpackage" --type dmg --name Lattice --app-version "$APP_VERSION" \
        --app-image "$PACKAGE_OUTPUT/Lattice.app" --dest "$PACKAGE_OUTPUT"
    cp "$PACKAGE_OUTPUT/Lattice-$APP_VERSION.dmg" "$DMG"
    printf 'Built %s\n' "$DMG"
fi
