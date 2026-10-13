#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_DIR"
JAVA_HOME_BUILD=${ACCESS_JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}
if [ ! -x "$JAVA_HOME_BUILD/bin/javac" ]; then JAVA_HOME_BUILD=$(/usr/libexec/java_home -v 17); fi
./scripts/fetch-deps.sh --offline
mkdir -p build/classes
find src/main/java -name '*.java' -print > build/sources.list
"$JAVA_HOME_BUILD/bin/javac" --release 17 -encoding UTF-8 -cp 'lib/*' -d build/classes @build/sources.list
# Finder's Java launcher uses the JAR manifest, not the shell's lib/* classpath.
# Resolve dependencies relative to build/access-for-mac.jar so double-clicking
# works regardless of the launcher's current directory.
printf 'Class-Path:' > build/classpath.mf
for dependency in lib/*.jar; do printf ' ../%s' "$dependency" >> build/classpath.mf; done
printf '\n\n' >> build/classpath.mf
"$JAVA_HOME_BUILD/bin/jar" --create --file build/access-for-mac.jar --main-class org.accessformac.ui.AccessForMacApp --manifest build/classpath.mf -C build/classes .
printf 'Built %s/build/access-for-mac.jar\n' "$PROJECT_DIR"
