#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_DIR"
./scripts/build.sh
JAVA_HOME_BUILD=${ACCESS_JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}
if [ ! -x "$JAVA_HOME_BUILD/bin/javac" ]; then JAVA_HOME_BUILD=$(/usr/libexec/java_home -v 17); fi
mkdir -p build/test-classes
find src/test/java -name '*.java' -print > build/tests.list
"$JAVA_HOME_BUILD/bin/javac" --release 17 -encoding UTF-8 -cp 'build/classes:lib/*' -d build/test-classes @build/tests.list
"$JAVA_HOME_BUILD/bin/java" -ea -Dfile.encoding=UTF-8 -cp 'build/classes:build/test-classes:lib/*' org.accessformac.db.DatabaseProof "$@"

for proof in org.accessformac.ui.UiFeatureProof org.accessformac.objects.ObjectCatalogProof org.accessformac.objects.NativeDesignProof; do
  proof_path=$(printf '%s' "$proof" | tr '.' '/')
  if [ -f "src/test/java/$proof_path.java" ]; then
    "$JAVA_HOME_BUILD/bin/java" -ea -Dfile.encoding=UTF-8 -cp 'build/classes:build/test-classes:lib/*' "$proof"
  fi
done
