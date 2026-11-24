#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
MODE="${1:?Missing mode}"

mkdir -p "$ROOT/out"

export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"

sw_vers
xcodebuild -version
java -version

chmod +x gradlew

printf '\nisFullBuild=false\n' >> gradle.properties
touch local.properties

if ! command -v xcodegen >/dev/null; then
    brew install xcodegen
fi

xcodegen --version

case "$MODE" in
    device)
        CONFIG=Release
        SDK=iphoneos
        DESTINATION="generic/platform=iOS"
        FRAMEWORK=ComposeApp.xcframework
        EMBED_SIGN=false

        ./gradlew \
          :composeApp:assembleComposeAppReleaseXCFramework \
          -PisFullBuild=false \
          -PiosDeviceOnly=true \
          -Dorg.gradle.jvmargs=-Xmx10g \
          --max-workers=2 \
          --stacktrace

        FRAMEWORK_SOURCE="$ROOT/composeApp/build/XCFrameworks/release/$FRAMEWORK"

        test -f "$FRAMEWORK_SOURCE/ios-arm64/ComposeApp.framework/ComposeApp"
        ;;

    simulator)
        CONFIG=Debug
        SDK=iphonesimulator
        DESTINATION="generic/platform=iOS Simulator"
        FRAMEWORK=ComposeApp.framework
        EMBED_SIGN=true

        ./gradlew \
          :composeApp:linkDebugFrameworkIosSimulatorArm64 \
          -PisFullBuild=false \
          -Dorg.gradle.jvmargs=-Xmx5g \
          --max-workers=2 \
          --stacktrace

        FRAMEWORK_SOURCE="$ROOT/composeApp/build/bin/iosSimulatorArm64/debugFramework/$FRAMEWORK"

        test -f "$FRAMEWORK_SOURCE/ComposeApp"
        ;;

    *)
        echo "Invalid build mode: $MODE"
        exit 1
        ;;
esac

mkdir -p iosApp/Frameworks
ditto "$FRAMEWORK_SOURCE" "iosApp/Frameworks/$FRAMEWORK"

mkdir -p iosApp/ci-beta/Generated
cd iosApp/ci-beta

python3 - <<'PY'
import plistlib

with open("../Info.plist", "rb") as f:
    p = plistlib.load(f)

p.update({
    "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)",
    "CFBundleExecutable": "$(EXECUTABLE_NAME)",
    "CFBundleName": "$(PRODUCT_NAME)",
    "CFBundlePackageType": "APPL",
    "CFBundleShortVersionString": "0.1.0",
    "CFBundleVersion": "1",
    "UIFileSharingEnabled": False,
    "LSSupportsOpeningDocumentsInPlace": False,
})

with open("Generated/Info.plist", "wb") as f:
    plistlib.dump(p, f)
PY

plutil -lint Generated/Info.plist

cat > project.yml <<EOF
name: SimpMusicBeta
options:
  deploymentTarget:
    iOS: "18.0"
targets:
  SimpMusic:
    type: application
    platform: iOS
    sources:
      - path: ../Sources/SimpMusic
    dependencies:
      - framework: ../Frameworks/$FRAMEWORK
        embed: true
        codeSign: $EMBED_SIGN
    settings:
      base:
        PRODUCT_NAME: SimpMusic
        PRODUCT_BUNDLE_IDENTIFIER: com.birao.simpmusic.beta
        INFOPLIST_FILE: Generated/Info.plist
        SWIFT_VERSION: "6.0"
        TARGETED_DEVICE_FAMILY: "1,2"
        DEVELOPMENT_TEAM: ""
        CODE_SIGN_STYLE: Manual
        ENABLE_USER_SCRIPT_SANDBOXING: NO
EOF

xcodegen generate --spec project.yml

BUILD_ARGS=(
    -project SimpMusicBeta.xcodeproj
    -scheme SimpMusic
    -configuration "$CONFIG"
    -sdk "$SDK"
    -destination "$DESTINATION"
    -derivedDataPath "$ROOT/iosApp/ci-beta/DerivedData"
)

if [ "$MODE" = device ]; then

    xcodebuild "${BUILD_ARGS[@]}" \
      CODE_SIGNING_ALLOWED=NO \
      CODE_SIGNING_REQUIRED=NO \
      CODE_SIGN_IDENTITY="" \
      build

else

    xcodebuild "${BUILD_ARGS[@]}" \
      CODE_SIGN_IDENTITY="-" \
      build
fi

APP="$ROOT/iosApp/ci-beta/DerivedData/Build/Products/$CONFIG-$SDK/SimpMusic.app"

test -d "$APP"
test -f "$APP/Info.plist"
test -f "$APP/SimpMusic"
test -f "$APP/Frameworks/ComposeApp.framework/ComposeApp"

plutil -lint "$APP/Info.plist"

if [ "$MODE" = device ]; then

    test "$(lipo -archs "$APP/SimpMusic")" = arm64

    test "$(lipo -archs "$APP/Frameworks/ComposeApp.framework/ComposeApp")" = arm64

    xcrun vtool -show-build "$APP/SimpMusic" |
      tee "$ROOT/out/macho-platform.txt"

    grep -Eq 'platform[[:space:]]+IOS[[:space:]]*$' \
      "$ROOT/out/macho-platform.txt"

    PAYLOAD="$ROOT/iosApp/ci-beta/package/Payload"
    mkdir -p "$PAYLOAD"

    ditto "$APP" "$PAYLOAD/SimpMusic.app"

    IPA="$ROOT/out/SimpMusic-Beta-0.1-arm64-unsigned.ipa"

    ditto -c -k --keepParent "$PAYLOAD" "$IPA"

    unzip -tq "$IPA"

    shasum -a 256 "$IPA" > "$IPA.sha256"

    echo "IPA_PACKAGE_VALIDATED"

else

    lipo -verify_arch arm64 "$APP/SimpMusic"

    xcrun vtool -show-build "$APP/SimpMusic" |
      tee "$ROOT/out/simulator-platform.txt"

    grep -q IOSSIMULATOR "$ROOT/out/simulator-platform.txt"

    SIMZIP="$ROOT/out/SimpMusic-Simulator-arm64.zip"

    ditto -c -k --keepParent "$APP" "$SIMZIP"

    unzip -tq "$SIMZIP"

    shasum -a 256 "$SIMZIP" > "$SIMZIP.sha256"

    echo "SIMULATOR_PACKAGE_VALIDATED"

    xcrun simctl list devices available -j > "$ROOT/out/simulators.json"

    read -r UDID STATE < <(
        python3 - "$ROOT/out/simulators.json" <<'PY'
import json
import sys

with open(sys.argv[1]) as f:
    devices = json.load(f)["devices"]

for runtime in sorted(devices, reverse=True):
    for d in devices[runtime]:
        if d.get("isAvailable") and d["name"].startswith("iPhone"):
            print(d["udid"], d["state"])
            sys.exit(0)

sys.exit("No available iPhone simulator")
PY
    )

    test -n "$UDID"

    if [ "$STATE" != Booted ]; then
        xcrun simctl boot "$UDID"
    fi

    xcrun simctl bootstatus "$UDID" -b
    xcrun simctl install "$UDID" "$APP"

    LAUNCH="$(xcrun simctl launch "$UDID" com.birao.simpmusic.beta)"

    echo "$LAUNCH"

    PID="$(printf '%s\n' "$LAUNCH" | awk -F ': ' '{print $NF}')"

    [[ "$PID" =~ ^[0-9]+$ ]]

    sleep 20

    xcrun simctl spawn "$UDID" launchctl list \
      > "$ROOT/out/simulator-processes.txt"

    awk -v pid="$PID" \
      '$1 == pid { found=1 } END { exit !found }' \
      "$ROOT/out/simulator-processes.txt"

    xcrun simctl io "$UDID" screenshot "$ROOT/out/launch.png"

    echo "SIMULATOR_STARTUP_VALIDATED"
fi