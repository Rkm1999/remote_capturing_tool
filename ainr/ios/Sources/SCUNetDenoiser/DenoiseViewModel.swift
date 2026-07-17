import Foundation
import OSLog
import Photos
import UIKit

enum PreviewMode: String, CaseIterable, Identifiable {
    case original = "Original"
    case result = "Result"

    var id: Self { self }
}

private struct ImportedSelection: @unchecked Sendable {
    let url: URL
    let name: String
    let preview: UIImage
    let width: Int
    let height: Int
}

@MainActor
final class DenoiseViewModel: ObservableObject {
    private static let logger = Logger(
        subsystem: "com.ryu.scunetdenoiser.ios",
        category: "Interface"
    )
    @Published var sourcePreview: UIImage?
    @Published var resultPreview: UIImage?
    @Published var sourceName = ""
    @Published var sourceWidth = 0
    @Published var sourceHeight = 0
    @Published var backend: DenoiseBackend {
        didSet { UserDefaults.standard.set(backend.rawValue, forKey: Self.backendKey) }
    }
    @Published var highOverlap: Bool {
        didSet { UserDefaults.standard.set(highOverlap, forKey: Self.highOverlapKey) }
    }
    @Published var previewMode: PreviewMode = .original
    @Published var progress: DenoiseProgress?
    @Published var status = "Choose a photo"
    @Published var isLoadingImage = false
    @Published var isProcessing = false
    @Published var isSaving = false
    @Published var result: DenoiseResult?
    @Published var alertMessage: String?
    @Published var processingStartedAt: Date?

    private static let backendKey = "SCUNetBackend"
    private static let highOverlapKey = "SCUNetHighOverlap"
    private let processor = SCUNetProcessor.shared
    private var sourceURL: URL?
    private var importTask: Task<Void, Never>?
    private var processingTask: Task<Void, Never>?
    private var runID: UUID?
    private var runAfterImport = false

    init() {
        highOverlap = UserDefaults.standard.bool(forKey: Self.highOverlapKey)
        let saved = UserDefaults.standard.string(forKey: Self.backendKey)
        if let requested = CommandLine.arguments.first(where: {
            $0.hasPrefix("--scunet-backend=")
        })?.split(separator: "=", maxSplits: 1).last,
           let requestedBackend = DenoiseBackend(rawValue: String(requested)) {
            backend = requestedBackend
        } else {
            backend = DenoiseBackend(rawValue: saved ?? "") ?? .neuralEngine
        }
        if CommandLine.arguments.contains("--scunet-autotest") {
            Task { [weak self] in
                self?.loadTestImage(runWhenReady: true)
            }
        }
    }

    var displayedImage: UIImage? {
        if previewMode == .result, let resultPreview { return resultPreview }
        return sourcePreview
    }

    var canRun: Bool {
        sourceURL != nil && !isLoadingImage && !isProcessing
    }

    var imageDetails: String {
        guard sourceWidth > 0, sourceHeight > 0 else { return "" }
        let megapixels = Double(sourceWidth * sourceHeight) / 1_000_000
        return "\(sourceWidth) x \(sourceHeight)  |  \(String(format: "%.1f MP", megapixels))"
    }

    func importURL(_ url: URL) {
        beginImport {
            let imported = try ImageCodec.importSource(url)
            return try Self.selection(url: imported.0, name: imported.1)
        }
    }

    func importPhotoData(_ data: Data, preferredExtension: String) {
        beginImport {
            let imported = try ImageCodec.importSource(
                data: data,
                preferredExtension: preferredExtension
            )
            return try Self.selection(url: imported.0, name: imported.1)
        }
    }

    func loadTestImage() {
        loadTestImage(runWhenReady: false)
    }

    private func loadTestImage(runWhenReady: Bool) {
        guard let url = Bundle.module.url(
            forResource: "SCUNet-Test-ISO51200",
            withExtension: "JPG",
            subdirectory: "Samples"
        ) else {
            alertMessage = "The bundled test image is missing"
            return
        }
        runAfterImport = runWhenReady
        beginImport {
            try Self.selection(url: url, name: "Checkerboard test - ISO 25600.JPG")
        }
    }

    func runDenoise() {
        guard let sourceURL, canRun else { return }
        processingTask?.cancel()
        let currentRun = UUID()
        runID = currentRun
        isProcessing = true
        processingStartedAt = Date()
        result = nil
        resultPreview = nil
        previewMode = .original
        status = "Preparing \(backend.rawValue). First run can take longer."
        let selectedBackend = backend
        let selectedHighOverlap = highOverlap
        let selectedName = sourceName

        processingTask = Task { [weak self] in
            guard let self else { return }
            do {
                let completed = try await processor.process(
                    sourceURL: sourceURL,
                    sourceName: selectedName,
                    backend: selectedBackend,
                    highOverlap: selectedHighOverlap
                ) { [weak self] update in
                    Task { @MainActor in
                        guard let self, self.runID == currentRun else { return }
                        self.apply(update)
                    }
                }
                try Task.checkCancellation()
                let preview = try ImageCodec.previewImage(completed.url)
                guard runID == currentRun else { return }
                result = completed
                resultPreview = preview
                previewMode = .result
                progress = nil
                isProcessing = false
                processingStartedAt = nil
                status = "Completed in \(Self.duration(completed.elapsedSeconds)) using \(completed.backendLabel)"
            } catch is CancellationError {
                guard runID == currentRun else { return }
                progress = nil
                isProcessing = false
                processingStartedAt = nil
                status = "Canceled"
            } catch {
                guard runID == currentRun else { return }
                Self.logger.error("Denoising failed: \(error.localizedDescription, privacy: .public)")
                progress = nil
                isProcessing = false
                processingStartedAt = nil
                status = "Denoising failed"
                alertMessage = error.localizedDescription
            }
        }
    }

    func cancelProcessing() {
        processingTask?.cancel()
        status = "Canceling after the current tile"
    }

    func saveResultToPhotos() {
        guard let result, !isSaving else { return }
        isSaving = true
        status = "Saving to Photos"
        Task { [weak self] in
            guard let self else { return }
            do {
                let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
                guard authorization == .authorized || authorization == .limited else {
                    throw SCUNetError(message: "Photo library access was not granted")
                }
                try await PHPhotoLibrary.shared().performChanges {
                    PHAssetChangeRequest.creationRequestForAssetFromImage(
                        atFileURL: result.url
                    )
                }
                isSaving = false
                status = "Saved to Photos"
            } catch {
                isSaving = false
                status = "Save failed"
                alertMessage = error.localizedDescription
            }
        }
    }

    func report(_ error: Error) {
        alertMessage = error.localizedDescription
    }

    private func beginImport(
        operation: @escaping @Sendable () throws -> ImportedSelection
    ) {
        importTask?.cancel()
        processingTask?.cancel()
        runID = nil
        isLoadingImage = true
        isProcessing = false
        processingStartedAt = nil
        progress = nil
        status = "Loading photo"
        importTask = Task { [weak self] in
            guard let self else { return }
            do {
                let imported = try await Task.detached(
                    priority: .userInitiated,
                    operation: operation
                ).value
                try Task.checkCancellation()
                sourceURL = imported.url
                sourceName = imported.name
                sourceWidth = imported.width
                sourceHeight = imported.height
                sourcePreview = imported.preview
                result = nil
                resultPreview = nil
                previewMode = .original
                isLoadingImage = false
                status = "Ready"
                if runAfterImport {
                    runAfterImport = false
                    runDenoise()
                }
            } catch is CancellationError {
                runAfterImport = false
                isLoadingImage = false
            } catch {
                runAfterImport = false
                isLoadingImage = false
                status = "Could not load photo"
                alertMessage = error.localizedDescription
            }
        }
    }

    private func apply(_ update: DenoiseProgress) {
        progress = update
        switch update.phase {
        case .loading:
            status = update.phase.rawValue
        case .preparing:
            status = "Preparing \(backend.rawValue). First run can take longer."
        case .processing:
            status = "Tile \(update.completedTiles) of \(update.totalTiles)"
        case .encoding:
            status = update.phase.rawValue
        }
    }

    private nonisolated static func selection(url: URL, name: String) throws -> ImportedSelection {
        let preview = try ImageCodec.previewImage(url)
        guard let dimensions = ImageCodec.imageDimensions(url) else {
            throw SCUNetError(message: "The image dimensions could not be read")
        }
        return ImportedSelection(
            url: url,
            name: name,
            preview: preview,
            width: dimensions.0,
            height: dimensions.1
        )
    }

    private static func duration(_ seconds: TimeInterval) -> String {
        if seconds < 60 { return String(format: "%.1f sec", seconds) }
        let minutes = Int(seconds) / 60
        return "\(minutes)m \(Int(seconds) % 60)s"
    }
}
