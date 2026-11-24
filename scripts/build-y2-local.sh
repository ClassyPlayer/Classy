#!/usr/bin/env bash
set -Eeuo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WINDOWS_OUTPUT="$SOURCE_DIR/OUTPUT"
BUILD_ROOT="$HOME/classy-y2-local-build"
PROJECT="$BUILD_ROOT/Classy-main"
SDK="$HOME/Android/Sdk"

fail() { echo; echo "BUILD STOPPED: $*" >&2; exit 1; }

if ! grep -qi microsoft /proc/version 2>/dev/null; then
  echo "This helper is designed for Ubuntu under WSL2 on Windows."
  echo "Run BUILD-Y2-WINDOWS.bat from the extracted Windows folder."
  exit 1
fi

mkdir -p "$WINDOWS_OUTPUT"

if [[ "$SOURCE_DIR" != "$PROJECT" ]]; then
  echo "==> Copying project to WSL's Linux filesystem (needed for firmware image mounting)"
  rm -rf "$PROJECT.new"
  mkdir -p "$BUILD_ROOT"
  cp -a "$SOURCE_DIR" "$PROJECT.new"
  rm -rf "$PROJECT.new/OUTPUT" "$PROJECT.new/app/build" "$PROJECT.new/.gradle" "$PROJECT.new/local.properties"
  rm -rf "$PROJECT"
  mv "$PROJECT.new" "$PROJECT"
fi

cd "$PROJECT"
chmod +x ./gradlew ./scripts/build-rom.sh

if ! command -v apt-get >/dev/null 2>&1; then fail "Ubuntu apt package manager was not found."; fi

echo "==> Installing build prerequisites"
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk curl unzip zip python3 e2fsprogs util-linux coreutils
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
mkdir -p "$SDK/cmdline-tools"

if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "==> Downloading Android command-line tools"
  TMP_TOOLS="$(mktemp -d)"
  curl -fL --retry 3 -o "$TMP_TOOLS/tools.zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q "$TMP_TOOLS/tools.zip" -d "$TMP_TOOLS/unpacked"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TMP_TOOLS/unpacked/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TMP_TOOLS"
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
echo "==> Accepting Android SDK licences and installing API 35 build components"
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null
set -o pipefail
"$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0" "build-tools;34.0.0"

printf 'sdk.dir=%s\n' "$SDK" > local.properties

echo "==> Running Y2 unit tests and compiling the modified Classy APK"
# The ROM needs the release variant: the debug variant has the id com.themoon.y1.seriesdebug, but the ROM installs
# the launcher as com.themoon.y1 (the real Classy id). With no keystore the release build is signed with the debug key.
rm -f "$WINDOWS_OUTPUT/rom_y2.zip" "$WINDOWS_OUTPUT/app-y2-debug.apk" "$WINDOWS_OUTPUT/SHA256SUMS.txt"
./gradlew --no-daemon --stacktrace testY2DebugUnitTest assembleY2Release
APK="$(find "$PROJECT/app/build/outputs/apk/y2/release" -name '*.apk' | head -1)"
[[ -n "$APK" && -s "$APK" ]] || fail "Gradle did not produce the Y2 release APK"

# Confirm that the APK has the native library required by the ROM builder.
unzip -Z1 "$APK" | grep -F -x 'lib/armeabi-v7a/libvlcjni.so' >/dev/null || fail "The APK is missing libvlcjni.so; refusing to create firmware."

echo "==> Checking WSL loop-mount support before modifying firmware images"
sudo mkdir -p /mnt/classy-loop-check
CHECK_IMG="$(mktemp "$BUILD_ROOT/loop-check.XXXXXX.img")"
truncate -s 8M "$CHECK_IMG"
if ! sudo mkfs.ext4 -F "$CHECK_IMG" >/dev/null 2>&1; then
  rm -f "$CHECK_IMG"
  fail "Could not create a test ext4 image. WSL may not support the required loop-mount operation."
fi
if ! sudo mount -t ext4 -o loop,rw "$CHECK_IMG" /mnt/classy-loop-check; then
  rm -f "$CHECK_IMG"
  fail "WSL could not mount an ext4 image with a loop device. No firmware was produced. A Linux environment with loop-mount support is required."
fi
sudo umount /mnt/classy-loop-check
rm -f "$CHECK_IMG"

mkdir -p "$BUILD_ROOT/output"
rm -f "$BUILD_ROOT/output/rom_y2.zip"
echo "==> Building the Updater CE Y2 firmware package"
sudo -v
bash ./scripts/build-rom.sh y2 --launcher-apk "$APK" "$BUILD_ROOT/output/rom_y2.zip"

[[ -s "$BUILD_ROOT/output/rom_y2.zip" ]] || fail "ROM builder finished without producing rom_y2.zip"

# Copy the deliverables back to the Windows folder.
cp -f "$APK" "$WINDOWS_OUTPUT/app-y2-debug.apk"   # file name kept for the .bat check; it is the release-variant APK, debug-signed
cp -f "$BUILD_ROOT/output/rom_y2.zip" "$WINDOWS_OUTPUT/rom_y2.zip"
sha256sum "$WINDOWS_OUTPUT/rom_y2.zip" "$WINDOWS_OUTPUT/app-y2-debug.apk" > "$WINDOWS_OUTPUT/SHA256SUMS.txt"

cat <<'DONE'

BUILD SUCCESSFUL
Files created in the Windows OUTPUT folder:
  rom_y2.zip          Y2 firmware archive for Updater CE
  app-y2-debug.apk    The matching debug APK (kept as a separate copy)
  SHA256SUMS.txt      Checksums for both files

IMPORTANT: Do not flash yet if you have not backed up your audiobook files,
Classy playback progress/bookmarks, and other internal storage. This ROM is
built from a clean base image; it is not a backup or a preservation of the
current player data. Confirm a tested backup and recovery plan first.
DONE
