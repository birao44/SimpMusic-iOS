# Keeping the iOS build alive

## Every week: re-sign

A free Apple ID signature lasts 7 days. Plug the iPhone in, unlock it, and double-click
`Reinstall SimpMusic.cmd` (in the `Simp Music` folder on the desktop), or in WSL:

```bash
bash /mnt/c/dev/SimpMusic/scripts/ios/reinstall.sh
```

It downloads the latest CI framework, builds, signs, installs, retries once if xtool stops with
"Error: unknown", and prints the date the new install runs out. Library, downloads and settings
are kept.

If the phone is not found: unlock it and tap Trust; replug the cable; if WSL still cannot see it,
the Windows firewall rule for TCP 27015 from the WSL subnet needs re-adding (run as admin):

```powershell
New-NetFirewallRule -DisplayName "WSL usbmuxd relay" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 27015 -RemoteAddress 172.30.176.0/20
```

If `xtool` says the Apple ID session expired, run `xtool auth login` in WSL yourself (it asks for
the Apple ID password, which only you should type).

## Monthly: take upstream changes

Both repositories have `upstream` remotes (maxrave-dev). Our work lives on the `ios` branches.

```bash
cd /c/dev/SimpMusic/core && git fetch upstream && git checkout ios && git rebase upstream/main
cd /c/dev/SimpMusic && git fetch upstream && git checkout ios && git rebase upstream/main
git add core && git rebase --continue   # if the submodule pointer conflicts
git -C core push --force-with-lease origin ios && git push --force-with-lease origin ios
```

Then type-check before waiting on CI:

```bash
./gradlew :data:compileKotlinIosArm64 :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinJvm --console=plain -q
```

Conflicts mostly land in the places iOS changed shared code: `getPlatform()` checks in screens,
`StreamRepositoryImpl.kt` (iOS branches), `SettingScreen.kt`, `App.kt` (deep links), DI modules.
Keep upstream's change and re-apply the iOS branch on top.

## CI

Push to `ios` builds the iPhone framework (about 15 min with a warm cache). GitHub's Apple-silicon
Mac runners are sometimes unavailable: the job is cancelled after 15 min with zero steps. Run the
same build on the Intel pool instead:

```bash
gh workflow run ios-framework.yml --repo Siddh68/SimpMusic --ref ios -f runner=macos-15-intel
```

## When something upstream of us breaks

- **Songs stop playing ("No URL found")**: YouTube changed the visionOS client. The extractor is
  `core/service/kotlinYtmusicScraper/src/iosMain/.../extractor/Extractor.ios.kt`; its client
  constants mirror PipePipe's `ClientsConstants` (VISIONOS_*). Bump them from PipePipe's latest
  release. The app log (Settings → export logs, or `Documents/logs/app.log`) shows
  `extract source=…` / `visionOS player refused …` lines.
- **Spotify import says "invalid query hash"**: Spotify rotated its web-player query hashes. Update
  the hashes in `core/service/spotify/.../library/SpotifyPathfinder.kt` from the current
  open.spotify.com web-player bundle.
- **Songs show the wrong length again**: the true length comes from the `dur=` parameter of the
  stream URL (`AvPlayerAdapter.kt`); check YouTube still sends it.

## Not possible with free signing

Direct Siri intents ("play X on SimpMusic"), a CarPlay app, push notifications and iCloud need a
paid Apple Developer account. Siri works through the "Play on SimpMusic" Shortcut, which opens
`simpmusic://play?q=<song>`.
