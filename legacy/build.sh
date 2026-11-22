#!/usr/bin/env bash
# Builds HtcReviveLegacy.apk for Sense 1/2 phones on Android 2.2+ (e.g. HTC Wildfire).
# Shares the HTTP server, Yahoo stock handler, request log, city table and TLS client with the
# Sense 5 build; adds its own 2.x service, root setup, UI and old-AccuWeather weather handler.
# TOOLS must contain android-8.jar, aapt2(.exe), r8.jar and apksig.jar.
# Native TLS lib (for Yahoo's HTTPS): cd legacy/jni && $NDK_R13B/ndk-build
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="${TOOLS:?set TOOLS to the directory with android-8.jar, aapt2, r8.jar and apksig.jar}"
AAPT2="$TOOLS/aapt2"; [ -x "$AAPT2.exe" ] && AAPT2="$AAPT2.exe"
ANDROID_JAR="$TOOLS/android-8.jar"
SHARED=../src/com/htcrevive

rm -rf build && mkdir -p build/classes build/dex build/res build/assets
cp ../assets/locations.tsv ../assets/roots.pem build/assets/
"$AAPT2" compile --dir ../res -o build/res/res.zip
VERSION_ARGS=()
[ -n "${VERSION_CODE:-}${VERSION_NAME:-}" ] && VERSION_ARGS+=(--replace-version)
[ -n "${VERSION_CODE:-}" ] && VERSION_ARGS+=(--version-code "$VERSION_CODE")
[ -n "${VERSION_NAME:-}" ] && VERSION_ARGS+=(--version-name "$VERSION_NAME")
"$AAPT2" link -o build/base.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" -A build/assets \
    --min-sdk-version 8 --target-sdk-version 8 ${VERSION_ARGS[@]+"${VERSION_ARGS[@]}"} build/res/res.zip
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
    -d build/classes $(find src -name '*.java') \
    $SHARED/MiniHttp.java $SHARED/Net.java $SHARED/StockHandler.java $SHARED/RequestLog.java \
    $SHARED/Locations.java $SHARED/WeatherHandler.java $SHARED/tls/*.java
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 8 --lib "$ANDROID_JAR" \
    --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)
if [ -d libs ]; then
    mkdir -p build/apklib/lib
    cp -r libs/* build/apklib/lib/
    (cd build/apklib && jar uf ../unsigned.apk lib)
fi

KEYSTORE=../certs/release.keystore
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p ../certs
    keytool -genkeypair -storetype PKCS12 -keystore "$KEYSTORE" -storepass htcrevive -keypass htcrevive \
        -alias htcrevive -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10000 -dname "CN=HtcRevive"
fi
java -cp "$TOOLS/apksig.jar" ../tools/ApkSign.java "$KEYSTORE" htcrevive htcrevive \
    build/unsigned.apk HtcReviveLegacy.apk 8
echo "built $(pwd)/HtcReviveLegacy.apk"
