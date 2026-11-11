import ComposeApp
import SwiftUI
import UIKit

/// The shared Compose UI (composeApp, `MainViewController()` in iosMain).
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
