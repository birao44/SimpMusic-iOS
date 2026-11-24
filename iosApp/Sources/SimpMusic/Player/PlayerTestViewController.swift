import UIKit

/// Manual test bench for `PlayerEngine`, opened with `simpmusic://playertest`.
/// Uses only the engine's public API, the way the Kotlin adapter will.
final class PlayerTestViewController: UIViewController, PlayerEngineListener {
    private struct Track {
        let name: String
        let url: String
        let startMs: Int64
    }

    // Public test media, checked with `curl -sIL` (200 + video/mp4 or x-mpegURL).
    private static let trackA = Track(
        name: "A (AAC 256k)",
        url: "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash-vod-single-segment/audio-141.mp4",
        startMs: 0
    )
    // Starts at 10 s to exercise seek-before-first-play.
    private static let trackB = Track(
        name: "B (AAC 128k @10s)",
        url: "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash-vod-single-segment/audio-140.mp4",
        startMs: 10_000
    )
    // Muxed video HLS on an audio-only handle: video tracks must stay disabled.
    private static let trackHLS = Track(
        name: "HLS (bipbop fMP4)",
        url: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        startMs: 0
    )
    // Answers 403: expect onError with httpStatus 403.
    private static let badURL = "https://storage.googleapis.com/exoplayer-test-media-1/m4a/sample.m4a"
    private static let artworkURL = "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg"

    private let engine = PlayerEngine.shared
    private var handle: Int32 = PlayerEngineConstants.noHandle
    private var track = trackA
    private var playing = false
    private var rate: Float = 1

    private var fadingIn: Int32 = PlayerEngineConstants.noHandle
    private var fadeTimer: Timer?
    private var readTimer: Timer?

    private let statusLabel = UILabel()
    private let logView = UITextView()
    private let clock: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        buildUI()
        engine.setListener(listener: self)
        engine.setRemoteCommandsEnabled(next: true, previous: true)
        log("listener set")
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        readTimer?.invalidate()
        readTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            // Scheduled on the main run loop.
            MainActor.assumeIsolated { self?.refreshStatus() }
        }
    }

    // MARK: UI

    private func buildUI() {
        let close = UIButton(type: .system)
        close.setTitle("Close", for: .normal)
        close.addAction(UIAction { [weak self] _ in self?.close() }, for: .touchUpInside)

        statusLabel.font = .monospacedDigitSystemFont(ofSize: 14, weight: .regular)
        statusLabel.numberOfLines = 2
        statusLabel.text = "no handle"

        let rows: [[(String, @MainActor () -> Void)]] = [
            [("Load A", { [weak self] in self?.load(Self.trackA) }),
             ("Load B", { [weak self] in self?.load(Self.trackB) }),
             ("Load HLS", { [weak self] in self?.load(Self.trackHLS) })],
            [("Play/Pause", { [weak self] in self?.togglePlay() }),
             ("−15 s", { [weak self] in self?.seek(by: -15_000) }),
             ("+15 s", { [weak self] in self?.seek(by: 15_000) })],
            [("Rate 1.0/1.25", { [weak self] in self?.toggleRate() }),
             ("Crossfade A→B", { [weak self] in self?.crossfade() }),
             ("Bad URL", { [weak self] in self?.loadBad() })],
            [("Clear log", { [weak self] in self?.logView.text = "" })],
        ]
        let grid = UIStackView(arrangedSubviews: rows.map { row in
            let stack = UIStackView(arrangedSubviews: row.map { title, action in
                let b = UIButton(configuration: .gray())
                b.setTitle(title, for: .normal)
                b.titleLabel?.adjustsFontSizeToFitWidth = true
                b.addAction(UIAction { _ in action() }, for: .touchUpInside)
                return b
            })
            stack.axis = .horizontal
            stack.distribution = .fillEqually
            stack.spacing = 8
            return stack
        })
        grid.axis = .vertical
        grid.spacing = 8

        logView.isEditable = false
        logView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        logView.backgroundColor = .secondarySystemBackground

        let header = UIStackView(arrangedSubviews: [statusLabel, close])
        header.axis = .horizontal
        header.alignment = .center
        close.setContentHuggingPriority(.required, for: .horizontal)

        let root = UIStackView(arrangedSubviews: [header, grid, logView])
        root.axis = .vertical
        root.spacing = 12
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)
        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            root.topAnchor.constraint(equalTo: guide.topAnchor, constant: 8),
            root.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -8),
            root.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 16),
            root.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -16),
        ])
    }

    private func refreshStatus() {
        guard handle != PlayerEngineConstants.noHandle else {
            statusLabel.text = "no handle"
            return
        }
        let s = { (ms: Int64) in String(format: "%.1f", Double(ms) / 1000) }
        statusLabel.text = "h\(handle) \(track.name)  rate \(rate)\n"
            + "pos \(s(engine.positionMs(handle: handle))) / dur \(s(engine.durationMs(handle: handle)))"
            + " / buf \(s(engine.bufferedMs(handle: handle))) s"
    }

    private func log(_ line: String) {
        let text = (logView.text ?? "") + "\(clock.string(from: Date())) \(line)\n"
        // Keep the view light during long sessions.
        logView.text = text.count > 20_000 ? String(text.suffix(15_000)) : text
        logView.scrollRangeToVisible(NSRange(location: (logView.text as NSString).length, length: 0))
    }

    // MARK: actions

    private func ensureHandle() -> Int32 {
        if handle == PlayerEngineConstants.noHandle {
            handle = engine.create(video: false)
            engine.setRate(handle: handle, rate: rate)
            log("create → h\(handle)")
        }
        return handle
    }

    private func load(_ t: Track) {
        cancelCrossfade()
        let h = ensureHandle()
        track = t
        engine.setVolume(handle: h, volume: 1)
        engine.load(handle: h, url: t.url, headers: [:], startMs: t.startMs, autoPlay: true, live: false)
        engine.setActiveHandle(handle: h)
        publishNowPlaying(t)
        log("load h\(h) \(t.name)")
    }

    private func loadBad() {
        let h = ensureHandle()
        engine.load(handle: h, url: Self.badURL, headers: [:], startMs: 0, autoPlay: true, live: false)
        log("load h\(h) bad URL (expect onError 403)")
    }

    private func publishNowPlaying(_ t: Track) {
        engine.setNowPlaying(title: "Test track \(t.name)", artist: "SimpMusic player test", album: "PlayerEngine",
                             artworkUrl: Self.artworkURL, durationMs: 0, live: false)
    }

    private func togglePlay() {
        guard handle != PlayerEngineConstants.noHandle else { return }
        if playing { engine.pause(handle: handle) } else { engine.play(handle: handle) }
    }

    private func seek(by deltaMs: Int64) {
        guard handle != PlayerEngineConstants.noHandle else { return }
        let target = max(engine.positionMs(handle: handle) + deltaMs, 0)
        engine.seek(handle: handle, positionMs: target)
        log("seek h\(handle) → \(target) ms")
    }

    private func toggleRate() {
        rate = rate == 1 ? 1.25 : 1
        if handle != PlayerEngineConstants.noHandle { engine.setRate(handle: handle, rate: rate) }
        log("rate → \(rate)")
    }

    /// Second handle on the other track; the 5 s equal-power ramp starts once it plays.
    private func crossfade() {
        guard handle != PlayerEngineConstants.noHandle, fadingIn == PlayerEngineConstants.noHandle else { return }
        let next = track.url == Self.trackA.url ? Self.trackB : Self.trackA
        let h = engine.create(video: false)
        fadingIn = h
        engine.setVolume(handle: h, volume: 0)
        engine.setRate(handle: h, rate: rate)
        engine.load(handle: h, url: next.url, headers: [:], startMs: next.startMs, autoPlay: true, live: false)
        // The incoming handle owns the lock screen, as the adapter will do.
        engine.setActiveHandle(handle: h)
        publishNowPlaying(next)
        track = next
        log("crossfade: create → h\(h), loading \(next.name)")
    }

    private func startFadeRamp() {
        let from = handle
        let to = fadingIn
        let start = Date()
        log("crossfade ramp h\(from) → h\(to)")
        // Invalidated through fadeTimer (close() / cancelCrossfade() also do it).
        fadeTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let t = min(Date().timeIntervalSince(start) / 5, 1)
                self.engine.setVolume(handle: from, volume: Float(cos(t * .pi / 2)))
                self.engine.setVolume(handle: to, volume: Float(sin(t * .pi / 2)))
                guard t >= 1 else { return }
                self.fadeTimer?.invalidate()
                self.fadeTimer = nil
                self.engine.release(handle: from)
                self.handle = to
                self.fadingIn = PlayerEngineConstants.noHandle
                self.log("crossfade done: released h\(from), current h\(to)")
            }
        }
    }

    private func cancelCrossfade() {
        fadeTimer?.invalidate()
        fadeTimer = nil
        if fadingIn != PlayerEngineConstants.noHandle {
            engine.release(handle: fadingIn)
            log("crossfade cancelled: released h\(fadingIn)")
            fadingIn = PlayerEngineConstants.noHandle
            if handle != PlayerEngineConstants.noHandle { engine.setActiveHandle(handle: handle) }
        }
    }

    private func close() {
        readTimer?.invalidate()
        cancelCrossfade()
        engine.releaseAll()
        engine.clearNowPlaying()
        handle = PlayerEngineConstants.noHandle
        dismiss(animated: true)
    }

    // MARK: PlayerEngineListener

    func onReady(handle: Int32, durationMs: Int64) {
        log("onReady h\(handle) duration=\(durationMs)")
    }

    func onPlayingChanged(handle: Int32, playing: Bool) {
        log("onPlayingChanged h\(handle) \(playing)")
        if handle == self.handle && fadingIn == PlayerEngineConstants.noHandle { self.playing = playing }
        if handle == fadingIn {
            self.playing = playing
            if playing && fadeTimer == nil { startFadeRamp() }
        }
    }

    func onBufferingChanged(handle: Int32, buffering: Bool) {
        log("onBufferingChanged h\(handle) \(buffering)")
    }

    func onEnded(handle: Int32) {
        log("onEnded h\(handle)")
    }

    func onError(handle: Int32, httpStatus: Int32, code: Int32, message: String) {
        log("onError h\(handle) http=\(httpStatus) code=\(code) \(message)")
    }

    func onVideoSize(handle: Int32, width: Int32, height: Int32) {
        log("onVideoSize h\(handle) \(width)x\(height)")
    }

    func onFirstVideoFrame(handle: Int32) {
        log("onFirstVideoFrame h\(handle)")
    }

    func onRemoteCommand(command: Int32, positionMs: Int64) {
        log("onRemoteCommand \(command) pos=\(positionMs)")
        // During a crossfade the incoming handle is what the lock screen shows.
        let target = fadingIn != PlayerEngineConstants.noHandle ? fadingIn : handle
        guard target != PlayerEngineConstants.noHandle else { return }
        switch command {
        case PlayerEngineConstants.remotePlay: engine.play(handle: target)
        case PlayerEngineConstants.remotePause: engine.pause(handle: target)
        case PlayerEngineConstants.remoteToggle: togglePlay()
        case PlayerEngineConstants.remoteSeek: engine.seek(handle: target, positionMs: positionMs)
        case PlayerEngineConstants.remoteNext, PlayerEngineConstants.remotePrevious:
            load(track.url == Self.trackA.url ? Self.trackB : Self.trackA)
        default: break
        }
    }

    func onInterruption(began: Bool, shouldResume: Bool) {
        log("onInterruption began=\(began) shouldResume=\(shouldResume)")
    }

    func onOutputDeviceLost() {
        log("onOutputDeviceLost")
    }
}
