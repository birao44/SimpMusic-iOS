import AVFoundation
import MediaPlayer
import Foundation

/// Main-actor state behind `PlayerEngine`: the handle table and every AVPlayer.
///
/// Observer callbacks are created in nonisolated helpers (`observeKVO`, `observeNotification`)
/// and hop with `runOnMain`, so a callback delivered on an AVFoundation thread never trips a
/// main-actor isolation check. Every callback re-finds its entry by handle and checks the
/// generation, which is what makes "no event after release / after the next load" hold.
@MainActor
final class PlayerEngineCore {
    @MainActor
    final class Entry {
        let handle: Int32
        let video: Bool
        let player = AVPlayer()
        var timeObserver: Any?
        var playerObservations: [NSKeyValueObservation] = []

        var item: AVPlayerItem?
        var itemObservations: [NSKeyValueObservation] = []
        var itemTokens: [NSObjectProtocol] = []
        /// Bumped by every load and release; callbacks from older items carry an older value.
        var generation: UInt64 = 0
        var live = false

        /// Remembered speed, applied through defaultRate so pause/play keeps it.
        var rate: Float = 1
        /// play() was asked for and not undone; honoured once the item is ready and seeked.
        var wantsPlay = false
        var ready = false
        var failed = false
        /// A seek asked for before readyToPlay (AVPlayerItem rejects seeks until then).
        var pendingSeekMs: Int64?
        /// Ready, but onReady waits for the start seek to land.
        var holdForInitialSeek = false
        var seekSeq: UInt64 = 0

        /// Last reported values, so only real changes become events.
        var playing = false
        var buffering = false

        init(handle: Int32, video: Bool) {
            self.handle = handle
            self.video = video
        }
    }

    let state: EngineShared
    var listener: (any PlayerEngineListener)?
    var entries: [Int32: Entry] = [:]

    // System integration (PlayerEngineCore+System.swift).
    var activeHandle: Int32 = PlayerEngineConstants.noHandle
    /// Added to the active handle's position on the lock screen (DJ segments); reset by setActiveHandle.
    var activeOffsetMs: Int64 = 0
    var sessionActive = false
    var nowPlaying: NowPlayingText?
    var artwork: MPMediaItemArtwork?
    var artworkToken: UInt64 = 0
    var systemTokens: [NSObjectProtocol] = []
    var commandTargets: [Any] = []

    init(state: EngineShared) {
        self.state = state
        installSystemObservers()
        installRemoteCommands()
    }

    // MARK: handle table

    /// The live entry for `handle`, building it if create() is still queued behind this call.
    func entry(_ handle: Int32) -> Entry? {
        if let e = entries[handle] { return e }
        guard let video = state.takePending(handle) else { return nil }

        let e = Entry(handle: handle, video: video)
        e.player.actionAtItemEnd = .pause
        if !video { e.player.preventsDisplaySleepDuringVideoPlayback = false }

        e.timeObserver = e.player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 4), queue: .main
        ) { [weak self] _ in
            // Delivered on the main queue, which is the main actor's executor.
            MainActor.assumeIsolated { self?.tick(handle) }
        }
        e.playerObservations = [
            observeKVO(e.player, \.timeControlStatus) { [weak self] in
                guard let self else { return }
                runOnMain { self.timeControlChanged(handle) }
            },
            observeKVO(e.player, \.rate) { [weak self] in
                guard let self else { return }
                runOnMain { self.tick(handle); self.publishNowPlayingIfActive(handle) }
            },
        ]
        entries[handle] = e
        state.insert(handle)
        return e
    }

    func construct(_ handle: Int32) { _ = entry(handle) }

    func release(_ handle: Int32) {
        _ = state.takePending(handle)
        guard let e = entries.removeValue(forKey: handle) else { return }
        detachItem(e)
        e.generation &+= 1
        if let observer = e.timeObserver { e.player.removeTimeObserver(observer) }
        e.timeObserver = nil
        e.playerObservations.forEach { $0.invalidate() }
        e.playerObservations.removeAll()
        state.remove(handle)
        if activeHandle == handle { activeHandle = PlayerEngineConstants.noHandle }
    }

    func releaseAll() {
        state.clearPending()
        for handle in Array(entries.keys) { release(handle) }
        deactivateSession()
    }

    // MARK: load

    func load(_ handle: Int32, url: String, headers: [String: String], startMs: Int64, autoPlay: Bool, live: Bool) {
        guard let e = entry(handle) else { return }
        detachItem(e)
        let gen = e.generation
        e.live = live

        guard let assetURL = URL(string: url), assetURL.scheme != nil else {
            // Queued, not inline: Kotlin may be inside load() on main.
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, let e = self.entries[handle], e.generation == gen, !e.failed else { return }
                    e.failed = true
                    self.listener?.onError(handle: handle, httpStatus: 0, code: Int32(NSURLErrorBadURL), message: "Invalid URL: \(url)")
                }
            }
            return
        }

        var options: [String: Any] = [:]
        if !headers.isEmpty { options["AVURLAssetHTTPHeaderFieldsKey"] = headers }
        let item = AVPlayerItem(asset: AVURLAsset(url: assetURL, options: options))
        // Speed changes keep pitch; spectral is the high-quality algorithm for music.
        item.audioTimePitchAlgorithm = .spectral
        e.item = item

        let start = max(startMs, 0)
        e.wantsPlay = autoPlay
        e.pendingSeekMs = start > 0 ? start : nil
        state.update(handle) { s in
            s = PlaybackSnapshot()
            s.mediaMs = start
            s.hostNs = DispatchTime.now().uptimeNanoseconds
            s.seekTargetMs = start > 0 ? start : nil
        }

        attachItemObservers(e, item: item, gen: gen)
        e.player.replaceCurrentItem(with: item)
        e.player.defaultRate = e.rate
        // With a start position, play waits for ready + seek so no audio from 0 leaks out.
        if autoPlay && e.pendingSeekMs == nil { startPlayback(e) }
    }

    /// Drops the current item silently: flags are reset before the pause, so the
    /// timeControlStatus change it causes produces no onPlayingChanged(false).
    private func detachItem(_ e: Entry) {
        e.generation &+= 1
        e.itemObservations.forEach { $0.invalidate() }
        e.itemObservations.removeAll()
        e.itemTokens.forEach { NotificationCenter.default.removeObserver($0) }
        e.itemTokens.removeAll()
        e.ready = false
        e.failed = false
        e.wantsPlay = false
        e.pendingSeekMs = nil
        e.holdForInitialSeek = false
        e.seekSeq &+= 1
        e.playing = false
        e.buffering = false
        if e.item != nil {
            e.item = nil
            e.player.pause()
            e.player.replaceCurrentItem(with: nil)
        }
    }

    private func attachItemObservers(_ e: Entry, item: AVPlayerItem, gen: UInt64) {
        let handle = e.handle
        var kvo = [
            observeKVO(item, \.status) { [weak self] in
                guard let self else { return }
                runOnMain { self.itemStatusChanged(handle, gen) }
            },
            observeKVO(item, \.loadedTimeRanges) { [weak self] in
                guard let self else { return }
                runOnMain { self.tick(handle) }
            },
            observeKVO(item, \.duration) { [weak self] in
                guard let self else { return }
                runOnMain { self.tick(handle) }
            },
        ]
        if !e.video {
            kvo.append(observeKVO(item, \.tracks) { [weak self] in
                guard let self else { return }
                runOnMain { self.disableVideoTracks(handle, gen) }
            })
        }
        e.itemObservations = kvo

        e.itemTokens = [
            observeNotification(AVPlayerItem.didPlayToEndTimeNotification, item) { [weak self] _ in
                guard let self else { return }
                runOnMain { self.itemEnded(handle, gen) }
            },
            observeNotification(AVPlayerItem.failedToPlayToEndTimeNotification, item) { [weak self] note in
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
                let code = Int32(truncatingIfNeeded: error?.code ?? 0)
                let message = error?.localizedDescription ?? "Playback failed"
                let http = httpStatus(inError: error)
                guard let self else { return }
                runOnMain { self.fail(handle, gen, httpFromError: http, code: code, message: message) }
            },
            observeNotification(AVPlayerItem.playbackStalledNotification, item) { [weak self] _ in
                guard let self else { return }
                runOnMain { self.tick(handle) }
            },
        ]
    }

    // MARK: item events

    private func itemStatusChanged(_ handle: Int32, _ gen: UInt64) {
        guard let e = entries[handle], e.generation == gen, let item = e.item, !e.failed else { return }
        switch item.status {
        case .readyToPlay:
            guard !e.ready else { return }
            e.ready = true
            tick(handle)
            if let target = e.pendingSeekMs {
                e.pendingSeekMs = nil
                e.holdForInitialSeek = true
                performSeek(e, target)
            } else {
                finishReady(e)
            }
        case .failed:
            let error = item.error as NSError?
            fail(handle, gen, httpFromError: httpStatus(inError: error),
                 code: Int32(truncatingIfNeeded: error?.code ?? 0),
                 message: error?.localizedDescription ?? "Item failed")
        default:
            break
        }
    }

    private func finishReady(_ e: Entry) {
        e.holdForInitialSeek = false
        let gen = e.generation
        listener?.onReady(handle: e.handle, durationMs: itemDurationMs(e))
        // The listener may have released or reloaded the handle inline.
        guard entries[e.handle] === e, e.generation == gen else { return }
        if e.wantsPlay && e.player.rate == 0 { startPlayback(e) }
        publishNowPlayingIfActive(e.handle)
    }

    private func fail(_ handle: Int32, _ gen: UInt64, httpFromError: Int32, code: Int32, message: String) {
        guard let e = entries[handle], e.generation == gen, !e.failed else { return }
        e.failed = true
        e.wantsPlay = false
        e.holdForInitialSeek = false
        e.pendingSeekMs = nil
        // Silent: the handle is dead until the next load, onError says so.
        e.playing = false
        e.buffering = false
        e.player.pause()
        let http = httpStatusFromErrorLog(e.item) ?? httpFromError
        state.update(handle) { $0.playing = false; $0.seekTargetMs = nil }
        listener?.onError(handle: handle, httpStatus: http, code: code, message: message)
    }

    private func itemEnded(_ handle: Int32, _ gen: UInt64) {
        guard let e = entries[handle], e.generation == gen, !e.live, !e.failed else { return }
        e.wantsPlay = false
        // The paused KVO can arrive after this notification; report it first to keep §3's order.
        if e.buffering {
            e.buffering = false
            listener?.onBufferingChanged(handle: handle, buffering: false)
        }
        if e.playing {
            e.playing = false
            listener?.onPlayingChanged(handle: handle, playing: false)
        }
        guard entries[handle] === e, e.generation == gen else { return }
        tick(handle)
        let duration = itemDurationMs(e)
        state.update(handle) { s in
            s.playing = false
            if duration > 0 { s.mediaMs = duration }
        }
        publishNowPlayingIfActive(handle)
        listener?.onEnded(handle: handle)
    }

    /// Audio-only handles never decode video: disable video tracks as they appear
    /// (HLS adds tracks after the first segments load).
    private func disableVideoTracks(_ handle: Int32, _ gen: UInt64) {
        guard let e = entries[handle], e.generation == gen, let item = e.item else { return }
        for track in item.tracks where track.isEnabled && track.assetTrack?.mediaType == .video {
            track.isEnabled = false
        }
    }

    private func timeControlChanged(_ handle: Int32) {
        guard let e = entries[handle] else { return }
        tick(handle)
        guard e.item != nil, !e.failed else { return }
        let status = e.player.timeControlStatus
        let playing = status == .playing
        let buffering = status == .waitingToPlayAtSpecifiedRate
            && e.player.reasonForWaitingToPlay != .noItemToPlay
        let gen = e.generation
        // Falses first so the adapter never sees playing and buffering both true.
        if !playing && e.playing {
            e.playing = false
            listener?.onPlayingChanged(handle: handle, playing: false)
        }
        if !buffering && e.buffering, entries[handle] === e, e.generation == gen {
            e.buffering = false
            listener?.onBufferingChanged(handle: handle, buffering: false)
        }
        if buffering && !e.buffering, entries[handle] === e, e.generation == gen {
            e.buffering = true
            listener?.onBufferingChanged(handle: handle, buffering: true)
        }
        if playing && !e.playing, entries[handle] === e, e.generation == gen {
            e.playing = true
            listener?.onPlayingChanged(handle: handle, playing: true)
        }
        publishNowPlayingIfActive(handle)
    }

    // MARK: transport

    func play(_ handle: Int32) {
        guard let e = entry(handle) else { return }
        e.wantsPlay = true
        guard e.item != nil, !e.failed else { return }
        // Deferred until readyToPlay + start seek; finishReady starts it.
        if e.pendingSeekMs != nil || e.holdForInitialSeek { return }
        startPlayback(e)
    }

    func pause(_ handle: Int32) {
        guard let e = entry(handle) else { return }
        e.wantsPlay = false
        e.player.pause()
        tick(handle)
        publishNowPlayingIfActive(handle)
    }

    private func startPlayback(_ e: Entry) {
        activateSessionIfNeeded()
        // play() starts at defaultRate (iOS 16+), so a remembered speed survives pause/play.
        e.player.defaultRate = e.rate
        e.player.play()
        tick(e.handle)
        publishNowPlayingIfActive(e.handle)
    }

    func seek(_ handle: Int32, positionMs: Int64) {
        guard let e = entry(handle), e.item != nil, !e.failed else { return }
        let target = max(positionMs, 0)
        state.update(handle) { $0.seekTargetMs = target }
        if !e.ready {
            // Hold playback so it does not start from the old position before the seek lands.
            if e.pendingSeekMs == nil { e.player.pause() }
            e.pendingSeekMs = target
            return
        }
        performSeek(e, target)
    }

    private func performSeek(_ e: Entry, _ targetMs: Int64) {
        e.seekSeq &+= 1
        let seq = e.seekSeq
        let gen = e.generation
        let handle = e.handle
        state.update(handle) { $0.seekTargetMs = targetMs }
        e.player.seek(
            to: CMTime(value: targetMs, timescale: 1000),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        ) { [weak self] _ in
            guard let self else { return }
            runOnMain { self.seekCompleted(handle, gen, seq) }
        }
    }

    private func seekCompleted(_ handle: Int32, _ gen: UInt64, _ seq: UInt64) {
        // An interrupted seek reports here too; only the latest one settles the state.
        guard let e = entries[handle], e.generation == gen, e.seekSeq == seq else { return }
        state.update(handle) { $0.seekTargetMs = nil }
        tick(handle)
        if e.holdForInitialSeek {
            finishReady(e)
        } else {
            publishNowPlayingIfActive(handle)
        }
    }

    func setVolume(_ handle: Int32, volume: Float) {
        guard let e = entry(handle) else { return }
        e.player.volume = min(max(volume, 0), 1)
    }

    func setRate(_ handle: Int32, rate: Float) {
        guard let e = entry(handle), rate > 0, rate.isFinite else { return }
        e.rate = rate
        e.player.defaultRate = rate
        // rate != 0 also while waiting for data, so a buffering player gets the new speed too.
        if e.player.rate != 0 { e.player.rate = rate }
        tick(handle)
        publishNowPlayingIfActive(handle)
    }

    // MARK: snapshot

    /// Re-samples position, rate, duration and buffer into the shared snapshot.
    func tick(_ handle: Int32) {
        guard let e = entries[handle] else { return }
        let time = e.player.currentTime()
        let media = time.isNumeric ? Int64((time.seconds * 1000).rounded()) : 0
        let playing = e.player.timeControlStatus == .playing
        let rate = e.player.rate
        let duration = itemDurationMs(e)
        let buffered = bufferedEndMs(e, at: time)
        let now = DispatchTime.now().uptimeNanoseconds
        state.update(handle) { s in
            s.mediaMs = max(media, 0)
            s.hostNs = now
            s.rate = rate
            s.playing = playing
            s.durationMs = duration
            s.bufferedMs = buffered
        }
    }

    func itemDurationMs(_ e: Entry) -> Int64 {
        guard !e.live, let item = e.item else { return 0 }
        let d = item.duration
        guard d.isNumeric, !d.isIndefinite, d.seconds > 0 else { return 0 }
        return Int64((d.seconds * 1000).rounded())
    }

    private func bufferedEndMs(_ e: Entry, at time: CMTime) -> Int64 {
        guard let item = e.item, time.isNumeric else { return 0 }
        for value in item.loadedTimeRanges {
            let range = value.timeRangeValue
            // Small slack: the playhead can sit a hair before a range that starts at a seek point.
            let start = range.start.seconds - 0.5
            let end = range.end.seconds
            if time.seconds >= start && time.seconds <= end {
                return Int64((end * 1000).rounded())
            }
        }
        return 0
    }

    /// HTTP status of the latest server failure in the item's error log, if any.
    private func httpStatusFromErrorLog(_ item: AVPlayerItem?) -> Int32? {
        guard let events = item?.errorLog()?.events else { return nil }
        for event in events.reversed() {
            if (400...599).contains(event.errorStatusCode) { return Int32(event.errorStatusCode) }
            if let comment = event.errorComment, let status = httpStatus(inText: comment) { return status }
            if let status = coreMediaHTTPStatus(domain: event.errorDomain, code: event.errorStatusCode) { return status }
        }
        return nil
    }
}

// MARK: - Nonisolated helpers

/// KVO registration whose handler is nonisolated, so AVFoundation may fire it on any thread.
func observeKVO<Root: NSObject, Value>(
    _ object: Root,
    _ keyPath: KeyPath<Root, Value>,
    _ fire: @escaping @Sendable () -> Void
) -> NSKeyValueObservation {
    object.observe(keyPath, options: []) { _, _ in fire() }
}

func observeNotification(
    _ name: Notification.Name,
    _ object: AnyObject?,
    _ handler: @escaping @Sendable (Notification) -> Void
) -> NSObjectProtocol {
    NotificationCenter.default.addObserver(forName: name, object: object, queue: nil, using: handler)
}

/// Walks an error and its underlying errors for an HTTP status.
func httpStatus(inError error: NSError?) -> Int32 {
    var current = error
    var depth = 0
    while let e = current, depth < 8 {
        if let status = httpStatus(inText: e.localizedDescription) { return status }
        if let reason = e.localizedFailureReason, let status = httpStatus(inText: reason) { return status }
        if let status = coreMediaHTTPStatus(domain: e.domain, code: e.code) { return status }
        // A progressive file answered with 403/404 has no error log and no "HTTP 403" text:
        // AVFoundation reports it as a URL-loading error (seen on device: -1102 for a 403).
        if e.domain == NSURLErrorDomain {
            if e.code == NSURLErrorNoPermissionsToReadFile { return 403 }
            if e.code == NSURLErrorFileDoesNotExist { return 404 }
        }
        current = e.userInfo[NSUnderlyingErrorKey] as? NSError
        depth += 1
    }
    return 0
}

/// CoreMedia describes server answers as "HTTP 403: Forbidden" in comments/descriptions.
func httpStatus(inText text: String) -> Int32? {
    guard let range = text.range(of: #"HTTP \d{3}"#, options: .regularExpression),
          let status = Int32(text[range].suffix(3)),
          (400...599).contains(status) else { return nil }
    return status
}

/// CoreMedia's own codes for HTTP failures: -12660 is a 403, -12938 a 404.
func coreMediaHTTPStatus(domain: String, code: Int) -> Int32? {
    guard domain == "CoreMediaErrorDomain" else { return nil }
    switch code {
    case -12660: return 403
    case -12938: return 404
    default: return nil
    }
}
