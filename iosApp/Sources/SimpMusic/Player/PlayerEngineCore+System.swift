import AVFoundation
import MediaPlayer
import UIKit

/// Lock-screen text supplied by Kotlin; elapsed time and rate come from the active handle.
struct NowPlayingText {
    var title: String
    var artist: String
    var album: String
    var durationMs: Int64
    var live: Bool
}

/// Audio session, MPNowPlayingInfoCenter and MPRemoteCommandCenter. The engine is app-lifetime,
/// so the system observers and command targets installed here are never removed.
extension PlayerEngineCore {
    // MARK: audio session

    func activateSessionIfNeeded() {
        guard !sessionActive else { return }
        do {
            // Category/mode/policy were set once by AppDelegate; only activation lives here.
            try AVAudioSession.sharedInstance().setActive(true)
            sessionActive = true
        } catch {
            NSLog("SimpMusic: AVAudioSession setActive(true) failed: \(error)")
        }
    }

    func deactivateSession() {
        guard sessionActive else { return }
        sessionActive = false
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            NSLog("SimpMusic: AVAudioSession setActive(false) failed: \(error)")
        }
    }

    func installSystemObservers() {
        let session = AVAudioSession.sharedInstance()
        systemTokens = [
            observeNotification(AVAudioSession.interruptionNotification, session) { [weak self] note in
                guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
                let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                let began = type == .began
                let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume)
                guard let self else { return }
                runOnMain { self.interruption(began: began, shouldResume: shouldResume) }
            },
            observeNotification(AVAudioSession.routeChangeNotification, session) { [weak self] note in
                guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
                guard let self else { return }
                runOnMain { self.listener?.onOutputDeviceLost() }
            },
        ]
    }

    private func interruption(began: Bool, shouldResume: Bool) {
        if began {
            // The system deactivated us; the next play() must activate again. Nothing resumes
            // on its own: a deferred autoplay is cancelled too, Kotlin decides.
            sessionActive = false
            for e in entries.values { e.wantsPlay = false }
        }
        listener?.onInterruption(began: began, shouldResume: shouldResume)
    }

    // MARK: now playing

    func setActiveHandle(_ handle: Int32) {
        activeHandle = handle
        activeOffsetMs = 0
        publishNowPlaying()
    }

    func setNowPlaying(title: String, artist: String, album: String, artworkUrl: String, durationMs: Int64, live: Bool) {
        nowPlaying = NowPlayingText(title: title, artist: artist, album: album, durationMs: durationMs, live: live)
        artworkToken &+= 1
        artwork = nil
        publishNowPlaying()

        guard !artworkUrl.isEmpty, let url = URL(string: artworkUrl) else { return }
        let token = artworkToken
        Task { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: url) else { return }
            // A newer setNowPlaying / clearNowPlaying owns the lock screen now.
            guard let self, self.artworkToken == token, let image = UIImage(data: data) else { return }
            self.artwork = makeArtwork(image)
            self.publishNowPlaying()
        }
    }

    func clearNowPlaying() {
        nowPlaying = nil
        artworkToken &+= 1
        artwork = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    func publishNowPlayingIfActive(_ handle: Int32) {
        if handle == activeHandle { publishNowPlaying() }
    }

    func publishNowPlaying() {
        guard let text = nowPlaying else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: text.title,
            MPMediaItemPropertyArtist: text.artist,
            MPMediaItemPropertyAlbumTitle: text.album,
            MPNowPlayingInfoPropertyIsLiveStream: text.live,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }

        let entry = entries[activeHandle]
        var durationMs = text.live ? 0 : text.durationMs
        if durationMs <= 0, !text.live, let entry { durationMs = itemDurationMs(entry) }
        if durationMs > 0 { info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000 }

        if let entry {
            let positionMs = max(0, state.positionMs(activeHandle, nowNs: DispatchTime.now().uptimeNanoseconds) + activeOffsetMs)
            // 0 while paused or buffering, so the lock-screen clock does not run ahead.
            let rate = entry.player.timeControlStatus == .playing ? entry.player.rate : 0
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs) / 1000
            info[MPNowPlayingInfoPropertyPlaybackRate] = Double(rate)
            info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = Double(entry.rate)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: remote commands

    func installRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        // Without skip intervals the lock screen shows next/previous instead of ±15 s.
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
        commandTargets = [
            forwardRemoteCommand(center.playCommand, PlayerEngineConstants.remotePlay, to: self),
            forwardRemoteCommand(center.pauseCommand, PlayerEngineConstants.remotePause, to: self),
            forwardRemoteCommand(center.togglePlayPauseCommand, PlayerEngineConstants.remoteToggle, to: self),
            forwardRemoteCommand(center.nextTrackCommand, PlayerEngineConstants.remoteNext, to: self),
            forwardRemoteCommand(center.previousTrackCommand, PlayerEngineConstants.remotePrevious, to: self),
            forwardRemoteCommand(center.changePlaybackPositionCommand, PlayerEngineConstants.remoteSeek, to: self),
        ]
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand,
                        center.nextTrackCommand, center.previousTrackCommand, center.changePlaybackPositionCommand] {
            command.isEnabled = true
        }
    }

    func setRemoteCommandsEnabled(next: Bool, previous: Bool) {
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = next
        center.previousTrackCommand.isEnabled = previous
    }

    func remoteCommand(_ command: Int32, positionMs: Int64) {
        listener?.onRemoteCommand(command: command, positionMs: positionMs)
    }
}

// MARK: - Nonisolated helpers

/// Command handlers are created outside the main actor so the closure carries no isolation
/// check; MediaPlayer calls them on main, and `runOnMain` makes that a guarantee either way.
private func forwardRemoteCommand(_ command: MPRemoteCommand, _ code: Int32, to core: PlayerEngineCore) -> Any {
    command.addTarget { [weak core] event in
        var positionMs: Int64 = 0
        if let seek = event as? MPChangePlaybackPositionCommandEvent {
            positionMs = Int64((seek.positionTime * 1000).rounded())
        }
        guard let core else { return .success }
        runOnMain { core.remoteCommand(code, positionMs: positionMs) }
        return .success
    }
}

/// MediaPlayer calls the artwork request handler on a background thread; built here so it is
/// not main-actor isolated (a Swift 6 isolation check there would crash).
private func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
    MPMediaItemArtwork(boundsSize: image.size) { _ in image }
}
