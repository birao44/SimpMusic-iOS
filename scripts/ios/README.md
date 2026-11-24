# iOS build (xtool, WSL)

The UI comes from the `ComposeApp.xcframework` built on macOS by
`.github/workflows/ios-framework.yml` and published to the `ios-framework-latest` release.
`iosApp/` is a thin xtool SwiftPM host around it. Run these in WSL (Ubuntu) with xtool
set up and signed in, and the iPhone connected over USB:

```bash
# 1. Download the latest CI framework (into ~/build/simpmusic-ios/Frameworks/)
bash /mnt/c/dev/SimpMusic/scripts/ios/fetch-framework.sh

# 2. Build the app only (output: ~/build/simpmusic-ios/xtool/SimpMusic.app)
bash /mnt/c/dev/SimpMusic/scripts/ios/build-install.sh

# 3. Build, sign and install on the phone (add --refresh to re-download the framework)
bash /mnt/c/dev/SimpMusic/scripts/ios/build-install.sh --install
```

`build-install.sh` sources `/mnt/c/dev/ios-scripts/ios-env.sh` so xtool can reach the
phone through the Windows usbmuxd.
