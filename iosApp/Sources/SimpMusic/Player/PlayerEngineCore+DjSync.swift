import AVFoundation
import CoreMedia

/// DJ transitions (docs/ios-player-bridge.md §7): starting one handle sample-aligned against
/// another handle's clock, and the lock-screen position offset used while a rendered segment plays.
extension PlayerEngineCore {
    /// Starts `handle` (loaded, paused at its start position) so that this position is heard exactly
    /// when `refHandle`'s item reaches `refMs`, with `AVPlayer.setRate(_:time:atHostTime:)` after a
    /// preroll. When that moment has already passed, the start position is advanced by the lateness
    /// so the two stay aligned. Nothing starts unless the reference is playing (Kotlin checks and
    /// falls back); a handle that is not ready yet starts like play().
    func playSynced(_ handle: Int32, refHandle: Int32, refMs: Int64) {
        guard let e = entry(handle), e.item != nil, !e.failed else { return }
        guard e.ready, e.pendingSeekMs == nil, !e.holdForInitialSeek, !e.player.isExternalPlaybackActive else {
            play(handle)
            return
        }
        e.wantsPlay = true
        let gen = e.generation
        // Required by setRate(_:time:atHostTime:); the handles synced here play local files.
        e.player.automaticallyWaitsToMinimizeStalling = false
        e.player.preroll(atRate: e.rate) { [weak self] _ in
            guard let self else { return }
            runOnMain { self.startSynced(handle, gen, refHandle: refHandle, refMs: refMs) }
        }
    }

    private func startSynced(_ handle: Int32, _ gen: UInt64, refHandle: Int32, refMs: Int64) {
        // pause() or a new load since the call: wantsPlay / generation say so.
        guard let e = entries[handle], e.generation == gen, e.wantsPlay, !e.failed, e.player.rate == 0 else { return }
        guard let ref = entries[refHandle], ref.player.rate != 0, let timebase = ref.item?.timebase else {
            e.wantsPlay = false
            return
        }
        let clock = CMClockGetHostTimeClock()
        var at = CMSyncConvertTime(CMTime(value: refMs, timescale: 1000), from: timebase, to: clock)
        guard at.isNumeric else {
            e.wantsPlay = false
            return
        }
        var itemTime = e.player.currentTime()
        let earliest = CMTimeAdd(CMClockGetTime(clock), CMTime(value: 40, timescale: 1000))
        if CMTimeCompare(at, earliest) < 0 {
            let late = CMTimeSubtract(earliest, at)
            itemTime = CMTimeAdd(itemTime, CMTimeMultiplyByFloat64(late, multiplier: Float64(ref.player.rate)))
            at = earliest
        }
        activateSessionIfNeeded()
        e.player.defaultRate = e.rate
        e.player.setRate(e.rate, time: itemTime, atHostTime: at)
        tick(handle)
        // The snapshot just taken still holds the start position; take it again once sound runs.
        let wait = max(0, CMTimeGetSeconds(CMTimeSubtract(at, CMClockGetTime(clock)))) + 0.03
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, let e = self.entries[handle], e.generation == gen else { return }
                self.tick(handle)
                self.publishNowPlayingIfActive(handle)
            }
        }
    }

    /// Lock-screen elapsed time = active handle's position + `offsetMs` (a rendered DJ segment
    /// shown on the timeline of the song it belongs to). setActiveHandle resets it to 0.
    func setActivePositionOffset(_ offsetMs: Int64) {
        activeOffsetMs = offsetMs
        publishNowPlaying()
    }
}
