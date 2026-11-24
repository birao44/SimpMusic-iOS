import AVFoundation
import Dispatch
import Foundation
import Synchronization

/// Swift half of the iOS player bridge (docs/ios-player-bridge.md §2, §3, §6).
///
/// Every method may be called from any thread. Mutations are forwarded to the main queue in
/// call order (inline when already on main); the three reads answer from `EngineShared` and
/// never hop. All AVFoundation state lives in the main-actor `PlayerEngineCore`.
///
/// Method names and labels match Kotlin `NativePlayerEngine`, so conformance can later be a
/// one-line extension. This file deliberately does not import ComposeApp.
final class PlayerEngine: Sendable {
    /// One engine per app: it owns the audio session, the lock screen and remote commands.
    @MainActor static let shared = PlayerEngine()

    private let state: EngineShared
    private let core: PlayerEngineCore
    /// Handle ids are never reused inside one process.
    private let nextHandle = Atomic<Int32>(0)

    @MainActor
    init() {
        let state = EngineShared()
        self.state = state
        self.core = PlayerEngineCore(state: state)
    }

    // MARK: lifecycle of one player

    func create(video: Bool) -> Int32 {
        let handle = nextHandle.add(1, ordering: .relaxed).newValue
        // Recorded before returning so a call on this handle that reaches main before the
        // queued construction can still build the player (see PlayerEngineCore.entry).
        state.addPending(handle, video: video)
        onMain { $0.construct(handle) }
        return handle
    }

    func load(handle: Int32, url: String, headers: [String: String], startMs: Int64, autoPlay: Bool, live: Bool) {
        onMain { $0.load(handle, url: url, headers: headers, startMs: startMs, autoPlay: autoPlay, live: live) }
    }

    func play(handle: Int32) { onMain { $0.play(handle) } }
    func pause(handle: Int32) { onMain { $0.pause(handle) } }
    func seek(handle: Int32, positionMs: Int64) { onMain { $0.seek(handle, positionMs: positionMs) } }
    func setVolume(handle: Int32, volume: Float) { onMain { $0.setVolume(handle, volume: volume) } }
    func setRate(handle: Int32, rate: Float) { onMain { $0.setRate(handle, rate: rate) } }
    func release(handle: Int32) { onMain { $0.release(handle) } }

    /// DJ join (docs/ios-player-bridge.md §7): start `handle` aligned to `refHandle` reaching `refMs`.
    func playSynced(handle: Int32, refHandle: Int32, refMs: Int64) {
        onMain { $0.playSynced(handle, refHandle: refHandle, refMs: refMs) }
    }

    // MARK: reads (any thread, never block on main)

    func positionMs(handle: Int32) -> Int64 {
        state.positionMs(handle, nowNs: DispatchTime.now().uptimeNanoseconds)
    }

    func durationMs(handle: Int32) -> Int64 { state.read(handle)?.durationMs ?? 0 }

    func bufferedMs(handle: Int32) -> Int64 { state.read(handle)?.bufferedMs ?? 0 }

    // MARK: system integration

    func setActiveHandle(handle: Int32) { onMain { $0.setActiveHandle(handle) } }

    func setNowPlaying(title: String, artist: String, album: String, artworkUrl: String, durationMs: Int64, live: Bool) {
        onMain {
            $0.setNowPlaying(title: title, artist: artist, album: album, artworkUrl: artworkUrl, durationMs: durationMs, live: live)
        }
    }

    func clearNowPlaying() { onMain { $0.clearNowPlaying() } }

    func setActivePositionOffset(offsetMs: Int64) { onMain { $0.setActivePositionOffset(offsetMs) } }

    func setRemoteCommandsEnabled(next: Bool, previous: Bool) {
        onMain { $0.setRemoteCommandsEnabled(next: next, previous: previous) }
    }

    // MARK: video (Phase 3)

    /// The AVPlayer behind `handle`, which Kotlin attaches an AVPlayerLayer to; nil when the
    /// handle is unknown or released. Kotlin only reads it and sets it on layers.
    @MainActor
    func player(handle: Int32) -> AVPlayer? { core.entry(handle)?.player }

    func setListener(listener: any PlayerEngineListener) { onMain { $0.listener = listener } }

    func releaseAll() { onMain { $0.releaseAll() } }

    // MARK: -

    private func onMain(_ work: @escaping @MainActor @Sendable (PlayerEngineCore) -> Void) {
        let core = self.core
        runOnMain { work(core) }
    }
}

/// Runs `work` on the main actor: inline when already on the main thread (keeps call order
/// for main-thread callers and avoids a hop), otherwise queued with `DispatchQueue.main.async`
/// (FIFO, so calls from one thread keep their order).
func runOnMain(_ work: @escaping @MainActor @Sendable () -> Void) {
    if Thread.isMainThread {
        MainActor.assumeIsolated { work() }
    } else {
        DispatchQueue.main.async { MainActor.assumeIsolated { work() } }
    }
}

/// Last known playback state of one handle, written on main, read from any thread.
struct PlaybackSnapshot: Sendable {
    var mediaMs: Int64 = 0
    /// `DispatchTime` uptime when `mediaMs` was sampled.
    var hostNs: UInt64 = 0
    var rate: Float = 0
    var playing = false
    var durationMs: Int64 = 0
    var bufferedMs: Int64 = 0
    /// Set from seek() until the seek completes; positionMs answers this meanwhile.
    var seekTargetMs: Int64?
}

/// The one lock-protected box shared between the main-actor core and the any-thread API.
/// It holds plain values only (no AVFoundation objects), so `Mutex` makes it Sendable
/// without `@unchecked`.
final class EngineShared: Sendable {
    private struct Tables {
        var snapshots: [Int32: PlaybackSnapshot] = [:]
        /// Handles returned by create() whose player is not built yet → their `video` flag.
        var pending: [Int32: Bool] = [:]
    }

    private let tables = Mutex(Tables())

    func addPending(_ handle: Int32, video: Bool) {
        tables.withLock { $0.pending[handle] = video }
    }

    /// Removes and returns the pending flag; nil once built or released.
    func takePending(_ handle: Int32) -> Bool? {
        tables.withLock { $0.pending.removeValue(forKey: handle) }
    }

    func clearPending() {
        tables.withLock { $0.pending.removeAll() }
    }

    func insert(_ handle: Int32) {
        tables.withLock { $0.snapshots[handle] = PlaybackSnapshot() }
    }

    func remove(_ handle: Int32) {
        tables.withLock { _ = $0.snapshots.removeValue(forKey: handle) }
    }

    func update(_ handle: Int32, _ body: (inout PlaybackSnapshot) -> Void) {
        tables.withLock { t in
            guard var snap = t.snapshots[handle] else { return }
            body(&snap)
            t.snapshots[handle] = snap
        }
    }

    func read(_ handle: Int32) -> PlaybackSnapshot? {
        tables.withLock { $0.snapshots[handle] }
    }

    /// §3: media + (now − host) × rate while playing, clamped to the duration; else media.
    func positionMs(_ handle: Int32, nowNs: UInt64) -> Int64 {
        guard let s = read(handle) else { return 0 }
        if let target = s.seekTargetMs { return target }
        guard s.playing, nowNs > s.hostNs else { return s.mediaMs }
        let elapsedMs = Double(nowNs - s.hostNs) / 1_000_000 * Double(s.rate)
        var position = s.mediaMs + Int64(elapsedMs)
        if s.durationMs > 0 { position = min(position, s.durationMs) }
        return max(position, 0)
    }
}
