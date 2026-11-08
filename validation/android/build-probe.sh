#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" ]] || { echo 'ANDROID_HOME or ANDROID_SDK_ROOT required' >&2; exit 2; }
build_tools="$sdk/build-tools/36.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
out="$root/build"
for executable in javac jar keytool; do command -v "$executable" >/dev/null; done
for executable in aapt d8 zipalign apksigner; do [[ -x "$build_tools/$executable" ]]; done
[[ -f "$android_jar" ]]
mkdir -p "$out/classes" "$out/dex" "$out/setup-classes" "$out/setup-dex"
# These are independent fixtures: no production Gradle settings or native source changes.
find "$root/java" -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -classpath "$android_jar" -d "$out/classes"
jar cf "$out/classes.jar" -C "$out/classes" .
"$build_tools/d8" --min-api 29 --lib "$android_jar" --output "$out/dex" "$out/classes.jar"
"$build_tools/aapt" package -f -M "$root/AndroidManifest.xml" -I "$android_jar" -F "$out/probe-unsigned.apk"
(cd "$out/dex" && "$build_tools/aapt" add "$out/probe-unsigned.apk" classes.dex)
"$build_tools/zipalign" -f -P 16 4 "$out/probe-unsigned.apk" "$out/probe-aligned.apk"
if [[ ! -f "$out/probe-test-key.jks" ]]; then
    keytool -genkeypair -keystore "$out/probe-test-key.jks" -storepass android -keypass android \
        -alias probe-test -keyalg RSA -keysize 2048 -validity 30 -dname 'CN=Piko isolated test fixture' >/dev/null
fi
"$build_tools/apksigner" sign --ks "$out/probe-test-key.jks" --ks-pass pass:android --key-pass pass:android \
    --out "$out/probe.apk" "$out/probe-aligned.apk"
"$build_tools/apksigner" verify "$out/probe.apk"
find "$root/setup" -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -classpath "$android_jar" -d "$out/setup-classes"
jar cf "$out/setup-classes.jar" -C "$out/setup-classes" .
"$build_tools/d8" --min-api 29 --lib "$android_jar" --output "$out/vpn-setup.zip" "$out/setup-classes.jar"
printf 'Probe APK: %s\nLab setup dex: %s\n' "$out/probe.apk" "$out/vpn-setup.zip"
