#!/usr/bin/env bash
# Checks that the compiled example build uses the locally published plugin
# (from the local Ivy repository), rather than one downloaded from Maven Central.
# Usage: check-example-uses-local-plugin.sh [version] (defaults to $PLUGIN_VERSION)
set -euo pipefail

cd "$(dirname "$0")/../.."

version="${1:-${PLUGIN_VERSION:-}}"
if [ -z "$version" ]; then
  echo "Usage: $0 <version> (or set PLUGIN_VERSION)" >&2
  exit 1
fi

CLASSPATH_FILE="example/out/mill-build/compileClasspath.json"
if [ ! -f "$CLASSPATH_FILE" ]; then
  echo "$CLASSPATH_FILE not found, compile the example first" >&2
  exit 1
fi

plugin_jars="$(grep -oE '[^":]*mill-native-image_mill1_3[^"]*\.jar' "$CLASSPATH_FILE" | sort -u || true)"
echo "mill-native-image JARs on the example build classpath:"
echo "${plugin_jars:-  (none)}"

if ! echo "$plugin_jars" | grep -F "/.ivy2/local/" | grep -qF "/$version/"; then
  echo "The example build doesn't use the locally published mill-native-image $version" >&2
  exit 1
fi
