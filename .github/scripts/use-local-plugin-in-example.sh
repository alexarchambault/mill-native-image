#!/usr/bin/env bash
# Makes the example use the plugin version built from the current commit,
# which should have been published beforehand with `./mill plugin[1].publishLocal`.
# Rewrites the plugin version in example/build.mill in place.
set -euo pipefail

cd "$(dirname "$0")/../.."

BUILD_FILE="example/build.mill"

version="$(./mill -i --ticker false show 'plugin[1].publishVersion' | tr -d '"')"
if [ -z "$version" ]; then
  echo "Could not compute the plugin version" >&2
  exit 1
fi

tmp="$(mktemp)"
sed -E "s/(mill-native-image::)[^[:space:]]+/\1$version/" "$BUILD_FILE" > "$tmp"
mv "$tmp" "$BUILD_FILE"

if ! grep -qF "mill-native-image::$version" "$BUILD_FILE"; then
  echo "Could not set the plugin version in $BUILD_FILE (no 'mill-native-image::<version>' dependency found)" >&2
  exit 1
fi

echo "The example now uses mill-native-image $version"
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "PLUGIN_VERSION=$version" >> "$GITHUB_ENV"
fi
