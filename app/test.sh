#!/bin/sh
# Unit tests for the agent's pure-Java code (HTTP layer, ambient brightness logic, dashboard requests), on a desktop JVM.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH="$JAVA_HOME/bin:$PATH"
OUT=$HERE/build/test
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 17 -d "$OUT" $(find "$HERE/src/com/braviafanboy/braviaagent/http" "$HERE/src/com/braviafanboy/braviaagent/ambient" "$HERE/test" -name '*.java') "$HERE/src/com/braviafanboy/braviaagent/dashboard/DashboardSpec.java" "$HERE/src/com/braviafanboy/braviaagent/dashboard/TokenBox.java"
java -cp "$OUT" com.braviafanboy.braviaagent.ambient.AmbientTest
java -cp "$OUT" com.braviafanboy.braviaagent.http.HttpTest
java -cp "$OUT" com.braviafanboy.braviaagent.dashboard.DashboardTest
