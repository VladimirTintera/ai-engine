import ComposeApp
import SwiftUI
import UIKit

@main
struct iOSApp: App {
    // Background URLSession events need a UIApplicationDelegate, also in a SwiftUI app.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // Apple Intelligence for Kotlin (iOS 26+), BEFORE the first use of the shared code
        // (ios/AppleTextModel.swift from the library repository).
        registerOnDeviceModel()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Model downloads run in a background URLSession; a download that finishes while the app is
    /// not running is delivered here (README, "Wire the providers", iOS).
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        if !IosModelDownloads.shared.handleEventsForBackgroundURLSession(
            identifier: identifier, completionHandler: completionHandler) {
            completionHandler() // not the library's session
        }
    }
}
