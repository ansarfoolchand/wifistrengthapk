#!/bin/bash
# Build WiFi Gauge APK (Android 16 / API 36) without Gradle.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-36/android.jar"

if [ ! -f "$BT/aapt2" ] || [ ! -f "$AJ" ]; then
    echo "Android SDK not found. Install: sdkmanager \"platforms;android-36\" \"build-tools;36.0.0\"" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "[2/7] aapt2 link (manifest + resources + R.java)"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
    -I "$AJ" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 24 \
    --target-sdk-version 36 \
    "$OUT/res.zip"

echo "[3/7] javac (Java 8 bytecode against android.jar)"
find "$ROOT/src" "$OUT/gen" -name '*.java' -print0 | \
    xargs -0 javac -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$AJ" \
    -classpath "$AJ" \
    -d "$OUT/classes"

echo "[4/7] d8 -> dex"
cd "$OUT/classes"
find . -name '*.class' -print0 | \
    xargs -0 "$BT/d8" --release --lib "$AJ" --min-api 24 --output "$OUT/dex"

echo "[5/7] add classes.dex to apk"
cd "$OUT"
zip -q -j app-unsigned.apk dex/classes.dex

echo "[6/7] zipalign"
"$BT/zipalign" -f 4 app-unsigned.apk app-aligned.apk

echo "[7/7] sign"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$ROOT/wifigauge.apk" app-aligned.apk

echo ""
echo "DONE -> $ROOT/wifigauge.apk"
"$BT/apksigner" verify --print-certs "$ROOT/wifigauge.apk" | head -3