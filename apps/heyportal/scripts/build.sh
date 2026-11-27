#!/usr/bin/env bash
#
# scripts/build.sh — Standalone build script for Hey Portal Test APK
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "=== Building Hey Portal Test APK ==="

ANDROID_JAR="/home/nuc/android-sdk/platforms/android-29/android.jar"
if [ ! -f "${ANDROID_JAR}" ]; then
    ANDROID_JAR="/home/nuc/android-sdk/platforms/android-36/android.jar"
fi
echo "Using Android Platform: ${ANDROID_JAR}"

AAPT2="/home/nuc/deep_carve/bt/aapt2"
ZIPALIGN="/home/nuc/deep_carve/bt/zipalign"
APKSIGNER="/home/nuc/deep_carve/bt/apksigner"
D8="$(find /home/nuc/android-sdk -name d8 2>/dev/null | head -n 1 || true)"
if [ -z "${D8}" ] || [ ! -f "${D8}" ]; then
    D8="/home/nuc/deep_carve/bt/d8"
fi

echo "Tools: AAPT2=${AAPT2}, D8=${D8}, ZIPALIGN=${ZIPALIGN}, APKSIGNER=${APKSIGNER}"

KEYSTORE="${HOME}/.android/debug.keystore"
if [ ! -f "${KEYSTORE}" ]; then
    mkdir -p "$(dirname "${KEYSTORE}")"
    keytool -genkeypair -v -keystore "${KEYSTORE}" -storepass android \
        -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

rm -rf build gen classes
mkdir -p build gen classes

echo "[1/6] Compiling resources..."
"${AAPT2}" compile --dir res -o build/comp.zip

echo "[2/6] Linking APK package..."
"${AAPT2}" link -o build/base.apk \
    -I "${ANDROID_JAR}" \
    --manifest AndroidManifest.xml \
    --java gen \
    --min-sdk-version 24 \
    --target-sdk-version 29 \
    build/comp.zip

echo "[3/6] Compiling Java classes..."
find src gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -nowarn -cp "${ANDROID_JAR}" -d classes @build/sources.txt

echo "[4/6] Running D8 DEX conversion..."
"${D8}" --min-api 24 --lib "${ANDROID_JAR}" --output build/ $(find classes -name '*.class')

echo "[5/6] Packaging APK..."
cp build/base.apk build/unsigned.apk
(cd build && zip -q -u unsigned.apk classes.dex)

echo "[6/6] Aligning & Signing..."
"${ZIPALIGN}" -f 4 build/unsigned.apk build/aligned.apk
"${APKSIGNER}" sign \
    --ks "${KEYSTORE}" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out heyportal.apk \
    build/aligned.apk

echo "SUCCESS! Created heyportal.apk"
ls -lh heyportal.apk
