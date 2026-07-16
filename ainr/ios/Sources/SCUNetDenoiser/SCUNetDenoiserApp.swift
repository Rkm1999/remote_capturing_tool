import SwiftUI

@main
struct SCUNetDenoiserApp: App {
    @StateObject private var viewModel = DenoiseViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(viewModel)
                .preferredColorScheme(.dark)
                .onOpenURL { viewModel.importURL($0) }
        }
    }
}
