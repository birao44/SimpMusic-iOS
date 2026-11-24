# iOS actuals of `:composeApp`

Every `expect` in `composeApp/src/commonMain` and what its iOS `actual` does today. iOS files live in
`composeApp/src/iosMain/kotlin/com/maxrave/simpmusic/`, mirroring the common path with a `.ios.kt`
suffix.

- **real**: does the job on iOS.
- **placeholder**: compiles and never crashes, and draws a stand-in where the user would otherwise
  see nothing (black video frame, "Not available on iOS yet").
- **no-op**: compiles and never crashes, does nothing (or returns an empty / "unsupported" value
  that makes the shared UI hide the feature).

| Expect declaration | iOS file | Status | Notes |
|---|---|---|---|
| `getPlatform()` | `Platform.ios.kt` | real | `Platform.iOS` |
| `copyToClipboard(label, text)` | `expect/CopyToClipboard.ios.kt` | real | `UIPasteboard.generalPasteboard` (iOS has no clip label) |
| `getDownloadFolderPath()` | `expect/FolderPath.ios.kt` | real | app Documents folder |
| `openUrl(url)` | `expect/OpenUrl.ios.kt` | real | `UIApplication.openURL` |
| `shareUrl(title, url)` | `expect/OpenUrl.ios.kt` | no-op | needs `UIActivityViewController` |
| `currentOrientation()` | `expect/Orientation.ios.kt` | real | `UIDevice.orientation` |
| `saveImageToDevice(bytes, fileName)` | `expect/ShareImage.ios.kt` | no-op | returns false; needs Photos |
| `shareImage(bytes, fileName, chooserTitle, mimeType)` | `expect/ShareImage.ios.kt` | no-op | returns false; needs share sheet |
| `toggleMiniPlayer()` | `expect/ToggleMiniPlayer.ios.kt` | no-op | desktop-only feature |
| `isOnUnmeteredNetwork()` | `expect/UnmeteredNetwork.ios.kt` | real | constant `true` for now; `NWPathMonitor.isExpensive` later |
| `formatOneDecimal(value)` | `expect/PlatformText.ios.kt` | real | always a `.` separator (JVM uses the locale's) |
| `formatGrouped(value)` | `expect/PlatformText.ios.kt` | real | `NSNumberFormatter` decimal style, current locale |
| `textBidi(text, rtlFallback)` | `expect/PlatformText.ios.kt` | real | first-strong-character rule; approximates `java.text.Bidi` |
| `desktopOsName()` | `expect/PlatformText.ios.kt` | real | never reached on iOS (`Platform.Desktop` only) |
| `Throwable.firstStackFrame(classPrefix)` | `expect/PlatformText.ios.kt` | no-op | null: native frames have no class/line split |
| `ImageBitmap.toByteArray()` | `expect/ui/Bitmap.ios.kt` | real | Skia JPEG, same as desktop |
| `ImageBitmap.toPngByteArray()` | `expect/ui/Bitmap.ios.kt` | real | Skia PNG |
| `coil3.Image.toImageBitmap()` | `expect/ui/Bitmap.ios.kt` | real | Skia bitmap |
| `ImageBitmap.toReadableBitmap(w, h)` | `expect/ui/Bitmap.ios.kt` | real | plain resize |
| `PlatformCastButton(modifier, tint)` | `expect/ui/CastButton.ios.kt` | no-op | no Cast sender |
| `isPlatformCastAvailable()` | `expect/ui/CastButton.ios.kt` | real | false |
| `rememberCastReceivers(discover)` | `expect/ui/CastButton.ios.kt` | no-op | empty list |
| `createWebViewCookieManager()` | `expect/ui/Cookies.ios.kt` | no-op | empty cookies |
| `PlatformWebView(...)` | `expect/ui/Cookies.ios.kt` | placeholder | "Not available on iOS yet"; needs `WKWebView` |
| `DiscordWebView(...)` | `expect/ui/Cookies.ios.kt` | placeholder | "Not available on iOS yet" |
| `rememberDeviceVolumeController()` | `expect/ui/DeviceVolumeController.ios.kt` | no-op | null hides the volume row |
| `filePickerResult(onResultUri)` | `expect/ui/FilePicker.ios.kt` | no-op | needs `UIDocumentPickerViewController` |
| `fileSaverResult(fileName, mimeType, onResultUri)` | `expect/ui/FilePicker.ios.kt` | no-op | same |
| `decodeImageBitmap(bytes)` | `expect/ui/ImageIo.ios.kt` | real | Skia decode |
| `persistPickedImage(bytes, fileName)` | `expect/ui/ImageIo.ios.kt` | real | okio write under Documents/playlist_covers |
| `LibrarySyncDialog(onDismiss)` | `expect/ui/LibrarySync.ios.kt` | no-op | closes itself; iOS takes no part in sync |
| `restartAfterSyncedSettings()` | `expect/ui/LibrarySync.ios.kt` | no-op | |
| `rememberLocalNetworkPermission()` | `expect/ui/LocalNetworkPermission.ios.kt` | no-op | iOS prompts by itself on first connection |
| `LoginSyncDialog(onDismiss)` | `expect/ui/LoginSync.ios.kt` | no-op | closes itself |
| `MediaPlayerView(...)` | `expect/ui/MediaPlayerView.ios.kt` | placeholder | black box; `onFirstFrame` never fires |
| `MediaPlayerViewWithSubtitle(...)` | `expect/ui/MediaPlayerView.ios.kt` | placeholder | black box |
| `rememberVideoAspectRatio(playerName)` | `expect/ui/MediaPlayerView.ios.kt` | no-op | null |
| `openEqResult(audioSessionId)` | `expect/ui/OpenEq.ios.kt` | no-op | no system EQ on iOS |
| `photoPickerResult(onResultUri)` | `expect/ui/PhotoPicker.ios.kt` | no-op | needs `PHPickerViewController` |
| `PlatformBackHandler(enabled, onBack)` | `expect/ui/PlatformBackHandler.ios.kt` | real | Compose MP `BackHandler` (edge swipe) |
| `isLyricsBlurSupported()` | `expect/ui/PlatformBlur.ios.kt` | real | true (skiko blur) |
| `platformDynamicColorScheme(isDark)` | `expect/ui/PlatformColorScheme.ios.kt` | real | null: no wallpaper colours on iOS |
| `isWallpaperDynamicColorSupported()` | `expect/ui/PlatformColorScheme.ios.kt` | real | false |
| `SystemBarAppearanceEffect(isDark)` | `expect/ui/PlatformColorScheme.ios.kt` | no-op | status bar style not driven yet |
| `rememberSaveImagePermission(onResult)` | `expect/ui/SaveImagePermission.ios.kt` | no-op | grants at once (saving itself is a no-op) |
| `HorizontalScrollBar(LazyListState)` | `expect/ui/Scrollbar.ios.kt` | no-op | touch: no scrollbars, as on Android |
| `HorizontalScrollBar(LazyGridState)` | `expect/ui/Scrollbar.ios.kt` | no-op | |
| `HorizontalScrollBar(CarouselState)` | `expect/ui/Scrollbar.ios.kt` | no-op | |
| `VerticalScrollBar(LazyListState)` | `expect/ui/Scrollbar.ios.kt` | no-op | |
| `VerticalScrollBar(LazyGridState)` | `expect/ui/Scrollbar.ios.kt` | no-op | |
| `VerticalScrollBar(ScrollState)` | `expect/ui/Scrollbar.ios.kt` | no-op | |
| `SongRecognitionSheet(onFind, onDismiss)` | `expect/ui/SongRecognition.ios.kt` | no-op | closes itself |
| `getScreenSizeInfo()` | `extension/UIExt.ios.kt` | real | `LocalWindowInfo.containerSize` |
| `KeepScreenOn()` | `extension/UIExt.ios.kt` | real | `UIApplication.idleTimerDisabled` while composed |
| `rememberIsInPipMode()` | `extension/UIExt.ios.kt` | no-op | false |
| `LiquidGlassAppBottomNavigationBar(...)` | `ui/component/LiquidGlassAppBottomNavigationBar.ios.kt` (+ `LiquidGlassTabBar.ios.kt`) | real | port of the Android bar; see below |
| `FullScreenRotationImmersive(onLaunch, onDispose)` | `ui/screen/player/FullscreenPlayer.ios.kt` | no-op | runs the callbacks; no forced landscape / hidden status bar |
| `calculateDataFraction(cacheRepository)` | `viewModel/SettingsViewModel.ios.kt` | no-op | null, as on desktop |
| `restoreNative(commonRepository, uri, getData)` | `viewModel/SettingsViewModel.ios.kt` | no-op | logs; unreachable until the picker exists |
| `readPickedFile(uri)` | `viewModel/SettingsViewModel.ios.kt` | no-op | empty array |
| `backupNative(commonRepository, uri, backupDownloaded)` | `viewModel/SettingsViewModel.ios.kt` | no-op | logs |
| `getPackageName()` | `viewModel/SettingsViewModel.ios.kt` | real | `NSBundle.mainBundle.bundleIdentifier` |
| `getFileDir()` | `viewModel/SettingsViewModel.ios.kt` | real | Documents folder |
| `changeLanguageNative(code)` | `viewModel/SettingsViewModel.ios.kt` | no-op | system language only for now |

Totals: 64 declarations — 26 real, 4 placeholder, 34 no-op.

## Glass tab bar

`LiquidGlassTabBar.ios.kt` is the Android file with one change: velocity timestamps come from
`TimeSource.Monotonic` instead of `android.os.SystemClock`.

`LiquidGlassAppBottomNavigationBar.ios.kt` is the Android file except for the layout that places the
toolbar and the mini player. Android uses `androidx.constraintlayout.compose.ConstraintLayout` with
`animateChangesSpec = tween(300)`, which has no iOS artifact; the iOS copy solves the same two
constraint sets by hand (`DecoupledBarLayout`) and animates between them the way ConstraintLayout
does (one tweened progress, each frame lerped and measured at that size, wrapped parent height
lerped too, changes queued until the running animation ends). Expected differences, unverified on a
device:

- The mini player's show/hide fades it at the other state's size, centred on where it would be —
  reproduced from ConstraintLayout's `WidgetFrame.interpolate`, but not compared frame by frame.
- Folded bar with a mini player: the 56dp card is centred on the 48dp circle and kept inside the
  wrapped parent (parent 56dp tall, card bottom-aligned to the circle). This follows
  ConstraintLayout's wrap-content rules as understood, not a measured Android layout.
