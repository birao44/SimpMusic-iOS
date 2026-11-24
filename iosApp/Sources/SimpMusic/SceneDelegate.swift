import ComposeApp
import UIKit

/// Hosts the Compose root in a full-screen window. The root view controller's view
/// fills the window including the status bar and home indicator areas, so Compose
/// draws edge-to-edge and applies safe-area insets itself (WindowInsets).
///
/// `@objc(SceneDelegate)` gives the class a stable Objective-C name so the
/// Info.plist `UISceneDelegateClassName` can be the plain string "SceneDelegate".
@objc(SceneDelegate)
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = MainViewControllerKt.MainViewController()
        window.makeKeyAndVisible()
        self.window = window
        // A cold launch from a URL delivers it here, not through openURLContexts.
        handle(connectionOptions.urlContexts)
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        handle(URLContexts)
    }

    /// `simpmusic://playertest` opens the player test bench; every other `simpmusic://` link
    /// (e.g. `simpmusic://play?q=…` from a Siri Shortcut) goes to the shared router in Kotlin.
    private func handle(_ contexts: Set<UIOpenURLContext>) {
        for context in contexts {
            let url = context.url
            if Self.isPlayerTest(url) {
                // Next run-loop turn: on cold launch the root is not in the window hierarchy yet.
                DispatchQueue.main.async { [weak self] in self?.presentPlayerTest() }
            } else if url.scheme?.lowercased() == "simpmusic" {
                MainViewControllerKt.openAppUrl(url: url.absoluteString)
            }
        }
    }

    private static func isPlayerTest(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "simpmusic" && url.host()?.lowercased() == "playertest"
    }

    /// Full screen over whatever Compose is showing; Close in the test screen dismisses it.
    private func presentPlayerTest() {
        guard var top = window?.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }
        if top is PlayerTestViewController { return }
        let test = PlayerTestViewController()
        test.modalPresentationStyle = .fullScreen
        top.present(test, animated: true)
    }
}
