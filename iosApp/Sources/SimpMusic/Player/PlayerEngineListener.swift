/// Swift stand-in for the Kotlin `NativePlayerEngineListener` (docs/ios-player-bridge.md §2).
/// Method names and labels match the Kotlin interface (Int → Int32, Long → Int64) so the
/// framework-backed listener can be adapted with a thin wrapper later.
///
/// `@MainActor` because the bridge contract delivers every event on the main thread;
/// `Sendable` so `PlayerEngine.setListener` can be called from any thread.
@MainActor
protocol PlayerEngineListener: AnyObject, Sendable {
    /// The item can play. `durationMs` is 0 for live. Fires once per load().
    func onReady(handle: Int32, durationMs: Int64)
    /// AVPlayer.timeControlStatus == .playing changed.
    func onPlayingChanged(handle: Int32, playing: Bool)
    /// Wants to play but is waiting for data. Never true while paused.
    func onBufferingChanged(handle: Int32, buffering: Bool)
    /// Reached the end. Never for live, never after release().
    func onEnded(handle: Int32)
    /// Load or playback failed. `httpStatus` is the server's answer (403 = expired URL), else 0.
    func onError(handle: Int32, httpStatus: Int32, code: Int32, message: String)
    /// Phase 3: not emitted yet.
    func onVideoSize(handle: Int32, width: Int32, height: Int32)
    /// Phase 3: not emitted yet.
    func onFirstVideoFrame(handle: Int32)
    /// Lock screen / Control Centre / headset. `command` is one of `PlayerEngineConstants.remote*`.
    func onRemoteCommand(command: Int32, positionMs: Int64)
    /// On `began` the system has already paused us; on end `shouldResume` is only a hint.
    func onInterruption(began: Bool, shouldResume: Bool)
    /// Headphones unplugged / Bluetooth gone; the system has already paused us.
    func onOutputDeviceLost()
}

/// Mirrors Kotlin `NativePlayerConstants`. Named differently so it cannot clash with the
/// class the ComposeApp header will export for the Kotlin object.
enum PlayerEngineConstants {
    static let remotePlay: Int32 = 1
    static let remotePause: Int32 = 2
    static let remoteToggle: Int32 = 3
    static let remoteNext: Int32 = 4
    static let remotePrevious: Int32 = 5
    /// `positionMs` carries the target.
    static let remoteSeek: Int32 = 6
    static let noHandle: Int32 = -1
}
