#!/bin/sh
# Unit tests for the agent's Android-free code, on a desktop JVM: the HTTP
# layer, the pairing and update rules (core/), ambient brightness, dashboard
# requests. Every class under test/ whose name ends in "Test" is run; nothing
# has to be listed here, so a test that is written is a test that runs.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH="$JAVA_HOME/bin:$PATH"
SRC=$HERE/src/com/braviafanboy/braviaagent
OUT=$HERE/build/test
rm -rf "$OUT"; mkdir -p "$OUT"
# What needs no Android: three whole packages, and two dashboard classes.
javac --release 17 -Xlint:all,-options -Werror -d "$OUT" \
  $(find "$SRC/http" "$SRC/core" "$SRC/ambient" "$HERE/test" -name '*.java') \
  "$SRC/dashboard/DashboardSpec.java" "$SRC/dashboard/TokenBox.java"
tests=$(cd "$HERE/test" && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g' | sort)
[ -n "$tests" ] || { echo "no test classes found under $HERE/test" >&2; exit 1; }
count=0
for t in $tests; do
  java -cp "$OUT" "$t"
  count=$((count + 1))
done
echo "ran $count test classes"
