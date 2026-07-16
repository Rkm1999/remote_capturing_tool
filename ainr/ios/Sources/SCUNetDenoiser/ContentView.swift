import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var viewModel: DenoiseViewModel
    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                preview
                controlPanel
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle("SCUNet Denoiser")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { importToolbar }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.image],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    viewModel.importURL(url)
                } else if case .failure(let error) = result {
                    viewModel.report(error)
                }
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    do {
                        guard let data = try await item.loadTransferable(type: Data.self) else {
                            throw SCUNetError(message: "The selected photo could not be loaded")
                        }
                        let fileExtension = item.supportedContentTypes.first?
                            .preferredFilenameExtension ?? "jpg"
                        viewModel.importPhotoData(data, preferredExtension: fileExtension)
                    } catch {
                        viewModel.report(error)
                    }
                    photoItem = nil
                }
            }
            .alert(
                "SCUNet Denoiser",
                isPresented: Binding(
                    get: { viewModel.alertMessage != nil },
                    set: { if !$0 { viewModel.alertMessage = nil } }
                )
            ) {
                Button("OK", role: .cancel) { viewModel.alertMessage = nil }
            } message: {
                Text(viewModel.alertMessage ?? "")
            }
        }
    }

    private var preview: some View {
        ZStack {
            Color(white: 0.055)
            if let image = viewModel.displayedImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if viewModel.isLoadingImage {
                ProgressView()
                    .controlSize(.large)
            } else {
                VStack(spacing: 16) {
                    Image(systemName: "photo.badge.plus")
                        .font(.system(size: 42, weight: .light))
                        .foregroundStyle(.secondary)
                    HStack(spacing: 10) {
                        PhotosPicker(selection: $photoItem, matching: .images) {
                            Label("Choose Photo", systemImage: "photo")
                        }
                        .buttonStyle(.borderedProminent)
                        Button(action: viewModel.loadTestImage) {
                            Label("Test Image", systemImage: "testtube.2")
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }

    private var controlPanel: some View {
        VStack(spacing: 11) {
            if viewModel.result != nil {
                Picker("Preview", selection: $viewModel.previewMode) {
                    ForEach(PreviewMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
            }

            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(viewModel.sourceName.isEmpty ? "No photo selected" : viewModel.sourceName)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(viewModel.imageDetails)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                Text(viewModel.status)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
            }

            Picker("Processor", selection: $viewModel.backend) {
                ForEach(DenoiseBackend.allCases) { backend in
                    Text(backend.pickerLabel).tag(backend)
                }
            }
            .pickerStyle(.segmented)
            .disabled(viewModel.isProcessing)

            if viewModel.isProcessing {
                VStack(spacing: 5) {
                    if let fraction = viewModel.progress?.fraction {
                        ProgressView(value: fraction)
                    } else {
                        ProgressView()
                    }
                    HStack {
                        Text(progressLabel)
                        Spacer()
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(elapsedLabel(at: context.date))
                        }
                    }
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
            }

            HStack(spacing: 12) {
                if viewModel.isProcessing {
                    Button(role: .cancel, action: viewModel.cancelProcessing) {
                        Label("Cancel", systemImage: "xmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button(action: viewModel.runDenoise) {
                        Label("Denoise", systemImage: "wand.and.stars")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!viewModel.canRun)
                }

                if let result = viewModel.result {
                    Button(action: viewModel.saveResultToPhotos) {
                        Image(systemName: viewModel.isSaving ? "hourglass" : "square.and.arrow.down")
                            .frame(width: 30)
                    }
                    .buttonStyle(.bordered)
                    .disabled(viewModel.isSaving)
                    .accessibilityLabel("Save to Photos")

                    ShareLink(item: result.url) {
                        Image(systemName: "square.and.arrow.up")
                            .frame(width: 30)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Share result")
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 12)
        .padding(.bottom, 10)
        .background(Color(white: 0.09))
    }

    @ToolbarContentBuilder
    private var importToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            PhotosPicker(selection: $photoItem, matching: .images) {
                Image(systemName: "photo.badge.plus")
            }
            .accessibilityLabel("Choose from Photos")
            Button { showFileImporter = true } label: {
                Image(systemName: "folder")
            }
            .accessibilityLabel("Choose from Files")
            Button(action: viewModel.loadTestImage) {
                Image(systemName: "testtube.2")
            }
            .accessibilityLabel("Load ISO 51200 test image")
        }
    }

    private var progressLabel: String {
        guard let progress = viewModel.progress else { return "Preparing" }
        if progress.phase == .processing {
            return "\(progress.completedTiles) / \(progress.totalTiles) tiles"
        }
        return progress.phase.rawValue
    }

    private func elapsedLabel(at date: Date) -> String {
        let seconds: TimeInterval
        if let started = viewModel.processingStartedAt {
            seconds = date.timeIntervalSince(started)
        } else if let elapsed = viewModel.progress?.elapsedSeconds {
            seconds = elapsed
        } else {
            return ""
        }
        if seconds < 60 { return String(format: "%.0fs", seconds) }
        return "\(Int(seconds) / 60)m \(Int(seconds) % 60)s"
    }
}
