#!/bin/sh
# Unit tests for the agent's pure-Java code (HTTP layer, ambient brightness logic), on a desktop JVM.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH="$JAVA_HOME/bin:$PATH"
OUT=$HERE/build/test
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 17 -d "$OUT" $(find "$HERE/src/com/braviafanboy/braviaagent/http" "$HERE/src/com/braviafanboy/braviaagent/ambient" "$HERE/test" -name '*.java')
java -cp "$OUT" com.braviafanboy.braviaagent.ambient.AmbientTest
java -cp "$OUT" com.braviafanboy.braviaagent.http.HttpTest
