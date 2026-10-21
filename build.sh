#!/usr/bin/env bash
# Builds HtcRevive.apk with aapt2 + javac + d8 + jarsigner (v1 signature, which API 17 needs).
# TOOLS must contain android-17.jar, aapt2(.exe) and r8.jar.
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="${TOOLS:?set TOOLS to the directory with android-17.jar, aapt2 and r8.jar}"
AAPT2="$TOOLS/aapt2"; [ -x "$AAPT2.exe" ] && AAPT2="$AAPT2.exe"
ANDROID_JAR="$TOOLS/android-17.jar"

bash tools/gen-certs.sh
rm -rf build && mkdir -p build/classes build/dex build/res build/stubs
"$AAPT2" compile --dir res -o build/res/res.zip
"$AAPT2" link -o build/base.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" -A assets \
    --min-sdk-version 17 --target-sdk-version 17 build/res/res.zip
# HTC Sense widgets live in the ROM (HTCCommonctrl.jar on the boot classpath), so compile
# against signature stubs that stay out of the dex.
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
    -d build/stubs $(find tools/htcstubs -name '*.java')
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -classpath build/stubs \
    -d build/classes $(find src -name '*.java')
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 17 --lib "$ANDROID_JAR" \
    --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)
# Bundle the native TLS lib (ndk-build writes libs/<abi>/*.so; the APK wants lib/<abi>/*.so).
if [ -d libs ]; then
    rm -rf build/apklib && mkdir -p build/apklib/lib
    cp -r libs/* build/apklib/lib/
    (cd build/apklib && jar uf ../unsigned.apk lib)
fi

if [ ! -f certs/release.keystore ]; then
    keytool -genkeypair -storetype PKCS12 -keystore certs/release.keystore -storepass htcrevive -keypass htcrevive \
        -alias htcrevive -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10000 -dname "CN=HtcRevive"
fi
# jarsigner's CMS signed attributes break PackageManager on Android < 4.3, so sign with apksig.
java -cp "$TOOLS/apksig.jar" tools/ApkSign.java certs/release.keystore htcrevive htcrevive \
    build/unsigned.apk HtcRevive.apk
echo "built $(pwd)/HtcRevive.apk"
