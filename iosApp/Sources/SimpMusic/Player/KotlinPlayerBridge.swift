import AVFoundation
import ComposeApp
import Foundation

/// What Kotlin holds as its `NativePlayerEngine`: an Objective-C visible object that forwards
/// every call to `PlayerEngine`. Kept apart from the engine so the engine itself needs no
/// ComposeApp import and the test screen can keep driving it directly.
///
/// The Swift names (`DomainNativePlayerEngine`, …) are the ones ComposeApp.h exports.
final class KotlinPlayerBridge: NSObject, DomainNativePlayerEngine, Sendable {
    private let engine: PlayerEngine

    init(engine: PlayerEngine) {
        self.engine = engine
    }

    func create(video: Bool) -> Int32 { engine.create(video: video) }

    func load(handle: Int32, url: String, headers: [String: String], startMs: Int64, autoPlay: Bool, live: Bool) {
        engine.load(handle: handle, url: url, headers: headers, startMs: startMs, autoPlay: autoPlay, live: live)
    }

    func play(handle: Int32) { engine.play(handle: handle) }
    func pause(handle: Int32) { engine.pause(handle: handle) }
    func seek(handle: Int32, positionMs: Int64) { engine.seek(handle: handle, positionMs: positionMs) }
    func setVolume(handle: Int32, volume: Float) { engine.setVolume(handle: handle, volume: volume) }
    func setRate(handle: Int32, rate: Float) { engine.setRate(handle: handle, rate: rate) }
    func release(handle: Int32) { engine.release(handle: handle) }

    func playSynced(handle: Int32, refHandle: Int32, refMs: Int64) {
        engine.playSynced(handle: handle, refHandle: refHandle, refMs: refMs)
    }

    func positionMs(handle: Int32) -> Int64 { engine.positionMs(handle: handle) }
    func durationMs(handle: Int32) -> Int64 { engine.durationMs(handle: handle) }
    func bufferedMs(handle: Int32) -> Int64 { engine.bufferedMs(handle: handle) }

    func setActiveHandle(handle: Int32) { engine.setActiveHandle(handle: handle) }

    func setNowPlaying(title: String, artist: String, album: String, artworkUrl: String, durationMs: Int64, live: Bool) {
        engine.setNowPlaying(title: title, artist: artist, album: album, artworkUrl: artworkUrl, durationMs: durationMs, live: live)
    }

    func clearNowPlaying() { engine.clearNowPlaying() }

    func setActivePositionOffset(offsetMs: Int64) { engine.setActivePositionOffset(offsetMs: offsetMs) }

    func setRemoteCommandsEnabled(next: Bool, previous: Bool) {
        engine.setRemoteCommandsEnabled(next: next, previous: previous)
    }

    /// Main thread only (the Kotlin contract); nil anywhere else.
    func nativePlayer(handle: Int32) -> Any? {
        guard Thread.isMainThread else { return nil }
        let engine = self.engine
        nonisolated(unsafe) var player: AVPlayer?
        MainActor.assumeIsolated { player = engine.player(handle: handle) }
        return player
    }

    func setListener(listener: any DomainNativePlayerEngineListener) {
        engine.setListener(listener: KotlinListenerAdapter(listener))
    }

    func releaseAll() { engine.releaseAll() }
}

/// The Kotlin listener seen through the engine's main-actor listener protocol.
///
/// The Kotlin object is not Sendable as far as Swift can tell, but it is only ever called from
/// the main thread (the engine's rule) and Kotlin/Native objects may be used from any thread,
/// so carrying the reference across is safe.
private final class KotlinListenerAdapter: PlayerEngineListener {
    private nonisolated(unsafe) let target: any DomainNativePlayerEngineListener

    nonisolated init(_ target: any DomainNativePlayerEngineListener) {
        self.target = target
    }

    func onReady(handle: Int32, durationMs: Int64) { target.onReady(handle: handle, durationMs: durationMs) }
    func onPlayingChanged(handle: Int32, playing: Bool) { target.onPlayingChanged(handle: handle, playing: playing) }
    func onBufferingChanged(handle: Int32, buffering: Bool) { target.onBufferingChanged(handle: handle, buffering: buffering) }
    func onEnded(handle: Int32) { target.onEnded(handle: handle) }

    func onError(handle: Int32, httpStatus: Int32, code: Int32, message: String) {
        target.onError(handle: handle, httpStatus: httpStatus, code: code, message: message)
    }

    func onVideoSize(handle: Int32, width: Int32, height: Int32) { target.onVideoSize(handle: handle, width: width, height: height) }
    func onFirstVideoFrame(handle: Int32) { target.onFirstVideoFrame(handle: handle) }
    func onRemoteCommand(command: Int32, positionMs: Int64) { target.onRemoteCommand(command: command, positionMs: positionMs) }
    func onInterruption(began: Bool, shouldResume: Bool) { target.onInterruption(began: began, shouldResume: shouldResume) }
    func onOutputDeviceLost() { target.onOutputDeviceLost() }
}
