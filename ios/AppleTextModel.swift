// The Kotlin framework of your app that exports :ai-core (export(...) in its framework block).
import ComposeApp
import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Kotlinový `OnDeviceTextModel` nad Apple Foundation Models (iOS 26+).
///
/// Kotlin/Native na `FoundationModels` nedosáhne (čistě Swiftové API), proto model drží Swift
/// a Kotlin (`AppleOnDeviceAi`) ho volá přes protokol. Zaregistruje ho `registerOnDeviceModel()`
/// při startu aplikace, dřív než vznikne `AppleOnDeviceAi` (v Plume v `iOSApp.init` před Koinem).
#if canImport(FoundationModels)
@available(iOS 26.0, *)
final class AppleTextModel: NSObject, OnDeviceTextModel {

    func availability() -> Int32 {
        switch SystemLanguageModel.default.availability {
        case .available:
            return OnDeviceTextStatus.shared.READY
        case .unavailable(.appleIntelligenceNotEnabled):
            return OnDeviceTextStatus.shared.NOT_ENABLED
        case .unavailable(.modelNotReady):
            return OnDeviceTextStatus.shared.NOT_READY
        case .unavailable:
            return OnDeviceTextStatus.shared.UNSUPPORTED
        }
    }

    /// Kontext systémového modelu je 4 096 tokenů (vstup i výstup); dotaz na něj je až od iOS 27.
    func contextSize() -> Int32 { 4096 }

    func generate(
        prompt: String,
        onText: @escaping (String) -> Void,
        onFinish: @escaping (String?) -> Void
    ) -> OnDeviceTextTask {
        let task = Task {
            do {
                // Nová relace na každý dotaz: Kotlin posílá celý prompt (shrnutí, návrh odpovědi…)
                // a historie předchozích dotazů by se jen pletla do kontextu.
                let session = LanguageModelSession()
                for try await snapshot in session.streamResponse(to: prompt) {
                    onText(snapshot.content)
                }
                onFinish(nil)
            } catch is CancellationError {
                onFinish(nil)
            } catch {
                onFinish(Self.code(of: error))
            }
        }
        return AppleTextTask(task: task)
    }

    /// Druh chyby podle JMÉNA případu: iOS 27 chyby přejmenoval (`LanguageModelError`) a jména
    /// platí pro obě verze, kdežto `switch` nad typem by na jedné z nich nepoznal nic.
    private static func code(of error: Error) -> String {
        let name = String(describing: error)
        if name.contains("exceededContextWindowSize") || name.contains("contextSizeExceeded") {
            return OnDeviceTextError.shared.CONTEXT
        }
        if name.contains("unsupportedLanguageOrLocale") { return OnDeviceTextError.shared.LANGUAGE }
        if name.contains("guardrailViolation") || name.contains("refusal") { return OnDeviceTextError.shared.REFUSED }
        if name.contains("rateLimited") || name.contains("concurrentRequests") { return OnDeviceTextError.shared.BUSY }
        // Simulátor na Macu s vypnutou Apple Intelligence hlásí model jako dostupný, ale
        // generování spadne na chybějících souborech modelu (`ModelManagerError` 1026).
        if name.contains("assetsUnavailable") || name.contains("ModelManagerError") {
            return OnDeviceTextError.shared.UNAVAILABLE
        }
        return name
    }
}

private final class AppleTextTask: NSObject, OnDeviceTextTask {
    private let task: Task<Void, Never>

    init(task: Task<Void, Never>) {
        self.task = task
    }

    func cancel() {
        task.cancel()
    }
}
#endif

/// Zaregistruje Apple Intelligence pro Kotlin, kde je (iOS 26+); jinde nechá AI v zařízení vypnutou.
func registerOnDeviceModel() {
    #if canImport(FoundationModels)
    if #available(iOS 26.0, *) {
        IosOnDeviceModel.shared.model = AppleTextModel()
    }
    #endif
}
