#!/usr/bin/env bash
# Installs the Android SDK into $HOME/android-sdk and points local.properties at it. Idempotent.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/cmdline-tools.zip" "$CMDLINE_URL"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true

need=()
[ -d "$SDK/platform-tools" ] || need+=("platform-tools")
[ -d "$SDK/platforms/android-35" ] || need+=("platforms;android-35")
[ -d "$SDK/build-tools/35.0.0" ] || need+=("build-tools;35.0.0")
if [ ${#need[@]} -gt 0 ]; then
  "$SDKMANAGER" --sdk_root="$SDK" "${need[@]}"
fi

touch "$ROOT/local.properties"
grep -v '^sdk\.dir=' "$ROOT/local.properties" > "$ROOT/local.properties.tmp" || true
echo "sdk.dir=$SDK" >> "$ROOT/local.properties.tmp"
mv "$ROOT/local.properties.tmp" "$ROOT/local.properties"
echo "Android SDK ready at $SDK"
