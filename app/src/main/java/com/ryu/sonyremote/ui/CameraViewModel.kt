package com.ryu.sonyremote.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ryu.sonyremote.data.SonyCameraRepository
import com.ryu.sonyremote.data.MovieActionResult
import com.ryu.sonyremote.data.PendingRemoteCapture
import com.ryu.sonyremote.data.CaptureDownloadProgress
import com.ryu.sonyremote.data.CaptureWorkspace
import com.ryu.sonyremote.data.ContinuousBatchResult
import com.ryu.sonyremote.data.DownloadedCapture
import com.ryu.sonyremote.data.SavedCapture
import com.ryu.sonyremote.model.CameraSettingId
import com.ryu.sonyremote.model.CapturedImage
import com.ryu.sonyremote.model.ConnectionState
import com.ryu.sonyremote.model.PostviewSizePreference
import com.ryu.sonyremote.model.OutputImageFormat
import com.ryu.sonyremote.processing.ComputationalImageEngine
import com.ryu.sonyremote.processing.BurstRateEstimator
import com.ryu.sonyremote.processing.BurstRateKey
import com.ryu.sonyremote.processing.FrameAlignmentException
import com.ryu.sonyremote.processing.JpegImageProcessor
import com.ryu.sonyremote.processing.LiveCompositeSession
import com.ryu.sonyremote.processing.LiveNdSession
import com.ryu.sonyremote.processing.LiveNdCaptureTracker
import com.ryu.sonyremote.processing.LiveNdFrameDisposition
import com.ryu.sonyremote.processing.LutPreset
import com.ryu.sonyremote.processing.CubeLut
import com.ryu.sonyremote.processing.PanoramaSession
import com.ryu.sonyremote.processing.PanoramaFinalRenderer
import com.ryu.sonyremote.processing.PanoramaRenderPlanner
import com.ryu.sonyremote.processing.PanoramaResourceBudget
import com.ryu.sonyremote.processing.PanoramaSourceDimensions
import java.io.IOException
import java.io.ByteArrayInputStream
import java.util.UUID
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

data class UiMessage(
    val text: String,
    val isError: Boolean = false,
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SonyCameraRepository(application)
    private val workspace = CaptureWorkspace(application.cacheDir)
    private val imageProcessor = JpegImageProcessor()
    private val preferences = application.getSharedPreferences("settings", 0)

    val connection: StateFlow<ConnectionState> = repository.connection
    val isStreaming: StateFlow<Boolean> = repository.isStreaming
    val isRecording: StateFlow<Boolean> = repository.isRecording
    val isPhysicalShutterTransferActive: StateFlow<Boolean> =
        repository.isPhysicalShutterTransferActive

    private val _isContinuousBurstActive = MutableStateFlow(false)
    val isContinuousBurstActive: StateFlow<Boolean> = _isContinuousBurstActive.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _permissionBlocked = MutableStateFlow(false)
    val permissionBlocked: StateFlow<Boolean> = _permissionBlocked.asStateFlow()
    private val _geotaggingEnabled = MutableStateFlow(preferences.getBoolean("geotagging", false))
    val geotaggingEnabled: StateFlow<Boolean> = _geotaggingEnabled.asStateFlow()
    private val _automaticPostviewPreference = MutableStateFlow(
        runCatching {
            PostviewSizePreference.valueOf(preferences.getString("download_quality", null) ?: "Original")
        }.getOrDefault(PostviewSizePreference.Original),
    )
    val automaticPostviewPreference: StateFlow<PostviewSizePreference> =
        _automaticPostviewPreference.asStateFlow()
    private val _outputImageFormat = MutableStateFlow(
        runCatching {
            OutputImageFormat.valueOf(preferences.getString("output_format", null) ?: "Jpeg")
        }.getOrDefault(OutputImageFormat.Jpeg),
    )
    val outputImageFormat: StateFlow<OutputImageFormat> = _outputImageFormat.asStateFlow()

    private val _captureMode = MutableStateFlow(CaptureMode.Photo)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    private val _liveNdFrames = MutableStateFlow(DEFAULT_LIVE_ND_FRAMES)
    val liveNdFrames: StateFlow<Int> = _liveNdFrames.asStateFlow()

    private val _liveNdStrategy = MutableStateFlow(StackCaptureStrategy.ContinuousBurst)
    val liveNdStrategy: StateFlow<StackCaptureStrategy> = _liveNdStrategy.asStateFlow()

    private val _captureSession = MutableStateFlow(CaptureSessionUiState())
    val captureSession: StateFlow<CaptureSessionUiState> = _captureSession.asStateFlow()

    private val _filmstrip = MutableStateFlow<List<FilmstripItem>>(emptyList())
    val filmstrip: StateFlow<List<FilmstripItem>> = _filmstrip.asStateFlow()

    private val _lutEditor = MutableStateFlow<LutEditorUiState?>(null)
    val lutEditor: StateFlow<LutEditorUiState?> = _lutEditor.asStateFlow()

    private val _lutCaptureState = MutableStateFlow(LutCaptureState())
    val lutCaptureState: StateFlow<LutCaptureState> = _lutCaptureState.asStateFlow()

    private val _lutPreviews = MutableStateFlow(
        LutPreset.entries.map { LutPreviewItem(preset = it, isLoading = true) },
    )
    val lutPreviews: StateFlow<List<LutPreviewItem>> = _lutPreviews.asStateFlow()

    private val _zoomTargetPercent = MutableStateFlow<Int?>(null)
    val zoomTargetPercent: StateFlow<Int?> = _zoomTargetPercent.asStateFlow()

    private var connectionJob: Job? = null
    private var liveViewJob: Job? = null
    private var commandJob: Job? = null
    private var pollJob: Job? = null
    private var liveViewRestartJob: Job? = null
    private var liveViewStartupFailures = 0
    private var lifecycleJob: Job? = null
    private var sessionJob: Job? = null
    private var lutJob: Job? = null
    private var physicalCaptureJob: Job? = null
    private var continuousBurstJob: Job? = null
    private var zoomTargetJob: Job? = null
    private var activeOriginalImportJob: Job? = null
    private val lutDerivativeJobs = mutableMapOf<String, Job>()
    private val completedLutDerivatives = mutableSetOf<String>()
    private var lutPreviewJob: Job? = null
    private var lastLutPreviewNanos = 0L
    private var activeOriginalImportId: String? = null
    private val originalImportRequests = Channel<String>(capacity = Channel.UNLIMITED)
    private val userCancelledOriginalImports = mutableSetOf<String>()
    private var liveNdBatchResults: Channel<ContinuousBatchResult>? = null
    private var panoramaSession: PanoramaSession? = null
    private var panoramaMetadataSource: ByteArray? = null
    private var panoramaPreviousDrive: String? = null
    private var panoramaFrameLimit = MIN_PANORAMA_FRAMES
    private val modeCameraSettings = mutableMapOf<CaptureMode, Map<CameraSettingId, String>>()
    private val panoramaSourceItems = mutableListOf<FilmstripItem>()
    private val burstRateEstimator = BurstRateEstimator()
    private val panoramaProcessorLock = Any()
    @Volatile private var sessionStopRequested = false
    private var isForeground = false
    private var isDisconnecting = false

    init {
        repository.setGeotaggingEnabled(_geotaggingEnabled.value)
        repository.setOutputImageFormat(_outputImageFormat.value)
        viewModelScope.launch(Dispatchers.IO) {
            val directory = getApplication<Application>().getDir(IMPORTED_LUT_DIRECTORY, 0)
            val stored = directory.listFiles().orEmpty().mapNotNull { file ->
                runCatching {
                    val label = String(Base64.decode(file.nameWithoutExtension, Base64.URL_SAFE or Base64.NO_WRAP))
                    ImportedLut(label, CubeLut.parse(file.readText()))
                }.getOrNull()
            }
            if (stored.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                stored.forEach { lut ->
                    if (_lutCaptureState.value.importedLuts.none { it.label == lut.label }) {
                        _lutCaptureState.value = _lutCaptureState.value.import(lut)
                    }
                }
            }
        }
        viewModelScope.launch {
            val saved = runCatching { repository.loadSavedGallery() }.getOrDefault(emptyList())
            val restoredWithoutRelations = saved.map { media ->
                val kind = captureKindForSavedName(media.name)
                FilmstripItem(
                    id = media.uri.toString(),
                    kind = kind,
                    title = captureTitleForSavedName(media.name),
                    source = CaptureAssetSource.MediaStore(media.uri),
                    thumbnail = media.thumbnail,
                    appliedLutName = media.lutName,
                    appliedLutStrength = media.lutStrength,
                    importedCapture = ImportedCapture(
                        id = media.uri.toString(),
                        originalUri = media.uri,
                    ).takeIf { kind == CaptureAssetKind.Photo },
                )
            }
            val pendingSources = mutableListOf<String>()
            val restored = restoredWithoutRelations.map { item ->
                when (item.kind) {
                    CaptureAssetKind.SourceFrame -> item.also { pendingSources += it.id }
                    CaptureAssetKind.LiveNd,
                    CaptureAssetKind.LiveComposite,
                    CaptureAssetKind.Panorama,
                    -> item.copy(relatedSourceIds = pendingSources.toList()).also { pendingSources.clear() }
                    else -> item
                }
            }
            if (restored.isNotEmpty()) {
                val restoredIds = restored.map(FilmstripItem::id).toSet()
                _filmstrip.value = restored + _filmstrip.value.filterNot { it.id in restoredIds }
            }
        }
        viewModelScope.launch {
            _lutCaptureState.collect { lut ->
                repository.setDownloadedImageProcessor { jpeg ->
                    if (_captureMode.value != CaptureMode.Photo || lut.isOriginal) {
                        jpeg
                    } else {
                        withContext(Dispatchers.Default) {
                            lut.importedLut?.takeIf { lut.importedSelected }?.let {
                                imageProcessor.applyLutToJpeg(jpeg, it.cube, lut.intensity)
                            } ?: imageProcessor.applyLutToJpeg(jpeg, lut.preset, lut.intensity)
                        }
                    }
                }
            }
        }
        viewModelScope.launch { workspace.clear() }
        viewModelScope.launch {
            var lastDecodedTimestamp = 0L
            var lastLutState = _lutCaptureState.value
            repository.latestFrame.combine(_lutCaptureState) { frame, lut -> frame to lut }
                .collectLatest { (frame, lut) ->
                if (
                    frame != null &&
                    lut == lastLutState &&
                    frame.timestampMillis - lastDecodedTimestamp < if (lut.isOriginal) {
                        PREVIEW_FRAME_INTERVAL_MILLIS
                    } else {
                        LUT_LIVE_PREVIEW_INTERVAL_MILLIS
                    }
                ) {
                    return@collectLatest
                }
                val lutChanged = lut != lastLutState
                if (frame != null) lastDecodedTimestamp = frame.timestampMillis
                lastLutState = lut
                val decoded = if (frame == null) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            lut.importedLut?.takeIf { lut.importedSelected }?.let {
                                imageProcessor.liveViewLutPreview(frame.jpeg, it.cube, lut.intensity)
                            } ?: imageProcessor.liveViewLutPreview(frame.jpeg, lut.preset, lut.intensity)
                        }.getOrElse {
                            BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                        }
                    }
                }
                val previous = _previewBitmap.value
                _previewBitmap.value = decoded
                if (decoded != null) liveViewStartupFailures = 0
                if (frame != null) scheduleLutPreviewStrip(frame.jpeg, force = lutChanged)
                if (previous != null && previous !== decoded) {
                    launch {
                        delay(PREVIEW_RECYCLE_DELAY_MILLIS)
                        if (_previewBitmap.value !== previous && !previous.isRecycled) previous.recycle()
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.physicalShutterCaptures.collect { capture ->
                val ndBatchResults = liveNdBatchResults
                if (
                    ndBatchResults != null &&
                    _captureSession.value.mode == CaptureMode.LiveNd &&
                    _captureSession.value.isActive
                ) {
                    return@collect
                }
                val routingJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        handlePhysicalShutterCapture(capture)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        _message.value = UiMessage(
                            error.readableMessage(
                                "Camera shutter photo was saved but could not be handled",
                            ),
                            true,
                        )
                    }
                }
                physicalCaptureJob = routingJob
                try {
                    routingJob.join()
                } finally {
                    if (physicalCaptureJob === routingJob) physicalCaptureJob = null
                }
            }
        }
        viewModelScope.launch {
            repository.physicalShutterFailures.collect { failure ->
                _message.value = UiMessage(failure, true)
            }
        }
        viewModelScope.launch {
            repository.physicalShutterReferences.collect { pending ->
                appendPendingPhysicalCapture(pending)
            }
        }
        viewModelScope.launch {
            repository.downloadProgress.collect(::updateDownloadProgress)
        }
        viewModelScope.launch {
            repository.continuousCaptureBatches.collect { result ->
                liveNdBatchResults?.send(result)
            }
        }
        viewModelScope.launch {
            for (captureId in originalImportRequests) {
                val job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    processOriginalImport(captureId)
                }
                activeOriginalImportId = captureId
                activeOriginalImportJob = job
                try {
                    job.join()
                } finally {
                    if (activeOriginalImportJob === job) activeOriginalImportJob = null
                    if (activeOriginalImportId == captureId) activeOriginalImportId = null
                }
            }
        }
    }

    fun setGeotaggingEnabled(enabled: Boolean) {
        _geotaggingEnabled.value = enabled
        repository.setGeotaggingEnabled(enabled)
        preferences.edit().putBoolean("geotagging", enabled).apply()
    }

    fun setAutomaticPostviewPreference(preference: PostviewSizePreference) = runCommand {
        repository.setAutomaticPostviewPreference(preference)
        _automaticPostviewPreference.value = preference
        preferences.edit().putString("download_quality", preference.name).apply()
    }

    fun setOutputImageFormat(format: OutputImageFormat) {
        _outputImageFormat.value = format
        repository.setOutputImageFormat(format)
        preferences.edit().putString("output_format", format.name).apply()
    }

    fun connect() {
        if (connectionJob?.isActive == true) return
        isDisconnecting = false
        connectionJob = viewModelScope.launch {
            _isBusy.value = true
            physicalCaptureJob?.cancelAndJoin()
            continuousBurstJob?.cancelAndJoin()
            activeOriginalImportJob?.cancelAndJoin()
            commandJob?.cancelAndJoin()
            pollJob?.cancelAndJoin()
            liveViewRestartJob?.cancelAndJoin()
            liveViewJob?.cancel()
            repository.cancelActiveIo()
            liveViewJob?.join()
            _isBusy.value = true
            try {
                _permissionBlocked.value = false
                val ready = repository.connect()
                runCatching { repository.setAutomaticPostviewPreference(_automaticPostviewPreference.value) }
                _message.value = UiMessage("Connected to ${ready.device.modelName}")
                if (isForeground && ready.capabilities.canLiveView) startLiveView()
                if (isForeground) startStatePolling()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(error.readableMessage("Could not connect to the camera"), true)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun disconnect() {
        isDisconnecting = true
        sessionStopRequested = true
        connectionJob?.cancel()
        commandJob?.cancel()
        sessionJob?.cancel()
        lutJob?.cancel()
        lutDerivativeJobs.values.forEach(Job::cancel)
        lutDerivativeJobs.clear()
        lutPreviewJob?.cancel()
        physicalCaptureJob?.cancel()
        continuousBurstJob?.cancel()
        activeOriginalImportJob?.cancel()
        pollJob?.cancel()
        liveViewRestartJob?.cancel()
        liveViewJob?.cancel()
        lifecycleJob?.cancel()
        repository.cancelActiveIo()
        connectionJob = viewModelScope.launch {
            lifecycleJob?.cancelAndJoin()
            commandJob?.cancelAndJoin()
            sessionJob?.cancelAndJoin()
            lutJob?.cancelAndJoin()
            physicalCaptureJob?.cancelAndJoin()
            continuousBurstJob?.cancelAndJoin()
            activeOriginalImportJob?.cancelAndJoin()
            pollJob?.cancelAndJoin()
            liveViewRestartJob?.cancelAndJoin()
            liveViewJob?.cancelAndJoin()
            liveViewJob = null
            withContext(NonCancellable) { restorePanoramaDriveBestEffort() }
            closeComputationalSessions()
            publishCaptureSession(CaptureSessionUiState())
            publishLutEditor(null)
            repository.disconnect()
            isDisconnecting = false
        }
    }

    fun capturePhoto() {
        pauseOriginalImportForCameraWork()
        runCommand {
            var placeholder: Bitmap? = withContext(Dispatchers.Default) {
                _previewBitmap.value?.takeUnless(Bitmap::isRecycled)?.copy(Bitmap.Config.ARGB_8888, false)
                    ?: Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.DKGRAY)
                    }
            }
            try {
                val preference = _automaticPostviewPreference.value
                val captured = repository.requestPhotoCapture(preference)
                appendPendingPhotoCapture(
                    remoteUri = captured.remoteUri,
                    isOriginal = captured.originalSizeRequested,
                    placeholder = requireNotNull(placeholder),
                    title = "Downloading",
                )
                placeholder = null
                val downloaded = repository.downloadCapturedPostview(captured)
                val saved = repository.saveCapture(
                    downloaded,
                    if (captured.originalSizeRequested) "SONY" else "SONY_PREVIEW",
                )
                if (!replacePendingPhysicalCapture(saved)) {
                    appendSavedAsset(saved, CaptureAssetKind.Photo, "Photo")
                }
                _message.value = UiMessage("Photo downloaded to Pictures/Sony Remote")
                repository.awaitPhotoCaptureReady()
            } finally {
                placeholder?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    fun requestOriginalImport(captureId: String) {
        val item = _filmstrip.value.firstOrNull { it.id == captureId } ?: return
        val capture = item.importedCapture ?: return
        val request = requestOriginalImport(capture)
        if (!request.shouldEnqueue) {
            if (capture.originalImportState == OriginalImportState.NotAvailable) {
                _message.value = UiMessage(
                    "Original is unavailable because this capture has no retained Original URL",
                    true,
                )
            }
            return
        }
        updateImportedCapture(captureId) { request.capture }
        originalImportRequests.trySend(captureId)
    }

    fun cancelOriginalImport(captureId: String) {
        userCancelledOriginalImports += captureId
        updateImportedCapture(captureId, ::cancelOriginalImport)
        if (activeOriginalImportId == captureId) {
            repository.cancelOriginalImportIo()
            activeOriginalImportJob?.cancel()
        }
    }

    fun startContinuousBurst() {
        if (_isBusy.value || _isContinuousBurstActive.value || continuousBurstJob?.isActive == true) return
        if (_captureMode.value != CaptureMode.Photo || _captureSession.value.isActive) return
        pauseOriginalImportForCameraWork()
        continuousBurstJob = viewModelScope.launch {
            var timedOut = false
            var startSucceeded = false
            _isContinuousBurstActive.value = true
            try {
                repository.startContinuousShooting(_automaticPostviewPreference.value)
                startSucceeded = true
                withTimeout(MAX_CONTINUOUS_BURST_MILLIS) { awaitCancellation() }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(
                    error.readableMessage("Continuous shooting could not start"),
                    true,
                )
            } finally {
                val stopFailure = withContext(NonCancellable) {
                    runCatching { repository.stopContinuousShooting() }.exceptionOrNull()
                }
                _isContinuousBurstActive.value = false
                continuousBurstJob = null
                when {
                    stopFailure != null && startSucceeded -> _message.value = UiMessage(
                        stopFailure.readableMessage("The camera did not confirm burst stop"),
                        true,
                    )
                    timedOut -> _message.value = UiMessage("Burst stopped at the 5 second safety limit")
                }
            }
        }
    }

    fun stopContinuousBurst() {
        continuousBurstJob?.cancel()
    }

    fun selectCaptureMode(mode: CaptureMode) {
        if (_isBusy.value || _captureSession.value.isActive) return
        val ready = connection.value as? ConnectionState.Ready
        val outgoing = _captureMode.value
        if (ready != null) {
            modeCameraSettings[outgoing] = ready.capabilities.settings.mapValues { it.value.currentWireValue }
        }
        synchronized(panoramaProcessorLock) { panoramaSession?.close() }
        panoramaSession = null
        _captureMode.value = mode
        publishCaptureSession(CaptureSessionUiState())
        if (ready != null) {
            runCommand {
                val desired = modeCameraSettings.getOrPut(mode) {
                    ready.capabilities.settings.mapValues { it.value.currentWireValue }
                }.toMutableMap()
                if (mode == CaptureMode.LiveNd) {
                    ready.capabilities.settings[CameraSettingId.ContShootingMode]
                        ?.options?.firstOrNull { it.wireValue.equals("Continuous", true) }
                        ?.let { desired[CameraSettingId.ContShootingMode] = it.wireValue }
                    highestBurstSpeed(ready.capabilities.settings[CameraSettingId.ContShootingSpeed])
                        ?.let { desired[CameraSettingId.ContShootingSpeed] = it }
                    modeCameraSettings[mode] = desired
                } else if (mode == CaptureMode.LiveComposite || mode == CaptureMode.Panorama) {
                    ready.capabilities.settings[CameraSettingId.ContShootingMode]
                        ?.options?.firstOrNull { it.wireValue.equals("Single", true) }
                        ?.let { desired[CameraSettingId.ContShootingMode] = it.wireValue }
                    modeCameraSettings[mode] = desired
                }
                desired.forEach { (id, value) ->
                    val current = (connection.value as? ConnectionState.Ready)
                        ?.capabilities?.settings?.get(id) ?: return@forEach
                    if (current.isWritable && current.currentWireValue != value) {
                        repository.setSetting(id, value)
                    }
                }
            }
        }
    }

    fun setLiveNdFrames(frames: Int) {
        if (_captureSession.value.isActive || _isBusy.value) return
        require(frames in LIVE_ND_FRAME_OPTIONS)
        _liveNdFrames.value = frames
    }

    fun setLiveNdStrategy(strategy: StackCaptureStrategy) {
        if (_captureSession.value.isActive || _isBusy.value) return
        _liveNdStrategy.value = strategy
    }

    fun primaryCaptureAction() {
        when (_captureMode.value) {
            CaptureMode.Photo -> capturePhoto()
            CaptureMode.LiveNd -> {
                if (_captureSession.value.isActive) requestComputationalStop() else startLiveNd()
            }
            CaptureMode.LiveComposite -> {
                if (_captureSession.value.isActive) requestComputationalStop() else startLiveComposite()
            }
            CaptureMode.Panorama -> {
                if (_captureSession.value.isActive) capturePanoramaFrame() else startPanorama()
            }
        }
    }

    fun finishPanorama() {
        val processor = panoramaSession ?: return
        if (!captureSession.value.canFinishPanorama || _isBusy.value) return
        runCommand {
            publishCaptureSession(_captureSession.value.copy(isFinishing = true))
            try {
                val (jpeg, originalResolution) = renderFinalPanorama(processor)
                val saved = repository.saveProcessedJpeg(
                    jpeg = jpeg,
                    prefix = "SONY_PANORAMA",
                    sourceCount = processor.frameCount,
                    metadataSourceJpeg = panoramaMetadataSource,
                )
                appendSavedAsset(
                    saved,
                    CaptureAssetKind.Panorama,
                    if (originalResolution) "Panorama" else "Panorama Preview",
                    sourceCount = processor.frameCount,
                    relatedSourceIds = panoramaSourceItems.map(FilmstripItem::id),
                )
                val restoreFailure = restorePanoramaSettingsBestEffort()
                synchronized(panoramaProcessorLock) { processor.close() }
                panoramaSession = null
                panoramaMetadataSource = null
                panoramaSourceItems.clear()
                repository.setComputationalCaptureImport(false)
                publishCaptureSession(_captureSession.value.copy(
                    isActive = false,
                    isFinishing = false,
                    processingProgress = null,
                ))
                _message.value = if (restoreFailure == null) {
                    UiMessage("Panorama saved to Pictures/Sony Remote")
                } else {
                    UiMessage(
                        "Panorama saved, but the camera did not restore Original postview size",
                        true,
                    )
                }
            } catch (error: Throwable) {
                synchronized(panoramaProcessorLock) { processor.close() }
                panoramaSession = null
                panoramaMetadataSource = null
                panoramaSourceItems.clear()
                repository.setComputationalCaptureImport(false)
                val restoreFailure = restorePanoramaSettingsBestEffort()
                publishCaptureSession(CaptureSessionUiState())
                if (error is CancellationException) throw error
                if (restoreFailure != null) {
                    throw IOException(
                        "${error.readableMessage("Panorama render failed")}; camera settings did not fully restore",
                        error,
                    )
                }
                throw error
            } finally {
                if (panoramaSession === processor) {
                    publishCaptureSession(_captureSession.value.copy(isFinishing = false))
                }
            }
        }
    }

    fun cancelPanorama() {
        if (_captureSession.value.mode != CaptureMode.Panorama) return
        if (_isBusy.value) {
            if (!_captureSession.value.isFinishing) return
            val renderJob = commandJob
            renderJob?.cancel()
            repository.cancelActiveIo()
            viewModelScope.launch {
                renderJob?.join()
                cleanupCancelledPanorama()
            }
            return
        }
        runCommand { cleanupCancelledPanorama() }
    }

    private suspend fun cleanupCancelledPanorama() {
        synchronized(panoramaProcessorLock) { panoramaSession?.close() }
        panoramaSession = null
        panoramaMetadataSource = null
        panoramaSourceItems.clear()
        repository.setComputationalCaptureImport(false)
        publishCaptureSession(CaptureSessionUiState())
        val restoreFailure = restorePanoramaSettingsBestEffort()
        _message.value = if (restoreFailure == null) {
            UiMessage("Panorama discarded; source photos were kept")
        } else {
            UiMessage(
                "Panorama stopped and sources were kept, but camera settings did not fully restore",
                true,
            )
        }
    }

    fun openLutEditor(item: FilmstripItem) {
        if (item.source is CaptureAssetSource.LiveViewPlaceholder) {
            _message.value = UiMessage("Import the captured photo before opening the LUT editor", true)
            return
        }
        lutJob?.cancel()
        val selected = _lutCaptureState.value
        val capturedPreset = item.appliedLutName?.let { name ->
            LutPreset.entries.firstOrNull { it.label == name }
        }
        val capturedImported = item.appliedLutName?.let { name ->
            selected.importedLuts.firstOrNull { it.label == name }
        }
        publishLutEditor(LutEditorUiState(
            item = item,
            preset = capturedPreset ?: selected.preset,
            intensity = item.appliedLutStrength ?: selected.intensity,
            importedLuts = selected.importedLuts,
            selectedImportedLabel = capturedImported?.label,
            isProcessing = true,
        ))
        lutJob = viewModelScope.launch {
            try {
                val jpeg = readAssetBytes(item.source)
                val preview = withContext(Dispatchers.Default) {
                    if (capturedImported != null) {
                        imageProcessor.editPreview(
                            jpeg, capturedImported.cube,
                            item.appliedLutStrength ?: selected.intensity, 0f, 0f, 0f,
                        )
                    } else imageProcessor.editPreview(
                        jpeg, capturedPreset ?: selected.preset,
                        item.appliedLutStrength ?: selected.intensity, 0f, 0f, 0f,
                    )
                }
                val thumbnails = withContext(Dispatchers.Default) {
                    LutPreset.entries.associateWith { preset ->
                        imageProcessor.lutThumbnail(
                            jpeg,
                            preset,
                            item.appliedLutStrength ?: selected.intensity,
                        )
                    }
                }
                val importedThumbnails = withContext(Dispatchers.Default) {
                    selected.importedLuts.associate { imported ->
                        imported.label to imageProcessor.lutThumbnail(
                            jpeg, imported.cube, item.appliedLutStrength ?: 1f,
                        )
                    }
                }
                if (_lutEditor.value?.item?.id == item.id) {
                    publishLutEditor(LutEditorUiState(
                        item = item,
                        preset = capturedPreset ?: selected.preset,
                        intensity = item.appliedLutStrength ?: selected.intensity,
                        preview = preview,
                        lutThumbnails = thumbnails,
                        importedLuts = selected.importedLuts,
                        selectedImportedLabel = capturedImported?.label,
                        importedLutThumbnails = importedThumbnails,
                    ))
                } else {
                    scheduleBitmapRecycle(preview)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                publishLutEditor(null)
                _message.value = UiMessage(error.readableMessage("Could not open this image"), true)
            }
        }
    }

    fun selectLut(preset: LutPreset) {
        val editor = _lutEditor.value ?: return
        _lutCaptureState.value = _lutCaptureState.value.copy(preset = preset)
        lutJob?.cancel()
        publishLutEditor(editor.copy(preset = preset, selectedImportedLabel = null, isProcessing = true))
        lutJob = viewModelScope.launch {
            try {
                val jpeg = readAssetBytes(editor.item.source)
                val preview = withContext(Dispatchers.Default) {
                    imageProcessor.editPreview(
                        jpeg, preset, editor.intensity,
                        editor.exposure, editor.contrast, editor.saturation,
                    )
                }
                val current = _lutEditor.value
                if (current?.item?.id == editor.item.id && current.preset == preset) {
                    publishLutEditor(current.copy(preview = preview, isProcessing = false))
                } else {
                    scheduleBitmapRecycle(preview)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                publishLutEditor(editor.copy(isProcessing = false))
                _message.value = UiMessage(error.readableMessage("Could not preview this LUT"), true)
            }
        }
    }

    fun selectEditorImportedLut(label: String) {
        val editor = _lutEditor.value ?: return
        val imported = editor.importedLuts.firstOrNull { it.label == label } ?: return
        lutJob?.cancel()
        publishLutEditor(editor.copy(selectedImportedLabel = label, isProcessing = true))
        lutJob = viewModelScope.launch {
            try {
                val jpeg = readAssetBytes(editor.item.source)
                val preview = withContext(Dispatchers.Default) {
                    imageProcessor.editPreview(
                        jpeg, imported.cube, editor.intensity,
                        editor.exposure, editor.contrast, editor.saturation,
                    )
                }
                val current = _lutEditor.value
                if (current?.item?.id == editor.item.id && current.selectedImportedLabel == label) {
                    publishLutEditor(current.copy(preview = preview, isProcessing = false))
                } else scheduleBitmapRecycle(preview)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                publishLutEditor(editor.copy(isProcessing = false))
                _message.value = UiMessage(error.readableMessage("Could not preview this LUT"), true)
            }
        }
    }

    fun selectLiveLut(preset: LutPreset) {
        _lutCaptureState.value = _lutCaptureState.value.select(preset)
        repository.latestFrame.value?.jpeg?.let { scheduleLutPreviewStrip(it, force = true) }
    }

    fun setLutIntensity(intensity: Float) {
        val normalized = intensity.coerceIn(0f, 1f)
        val state = _lutCaptureState.value
        _lutCaptureState.value = if (state.importedSelected) {
            state.withImportedIntensity(normalized)
        } else {
            state.withIntensity(state.preset, normalized)
        }
        repository.latestFrame.value?.jpeg?.let { scheduleLutPreviewStrip(it, force = true) }
        val editor = _lutEditor.value ?: return
        lutJob?.cancel()
        publishLutEditor(editor.copy(intensity = normalized, isProcessing = true))
        lutJob = viewModelScope.launch {
            try {
                val jpeg = readAssetBytes(editor.item.source)
                val preview = withContext(Dispatchers.Default) {
                    val imported = editor.importedLuts.firstOrNull { it.label == editor.selectedImportedLabel }
                    if (imported != null) imageProcessor.editPreview(
                        jpeg, imported.cube, normalized,
                        editor.exposure, editor.contrast, editor.saturation,
                    ) else imageProcessor.editPreview(
                        jpeg, editor.preset, normalized,
                        editor.exposure, editor.contrast, editor.saturation,
                    )
                }
                val current = _lutEditor.value
                if (current?.item?.id == editor.item.id && current.intensity == normalized) {
                    publishLutEditor(current.copy(preview = preview, isProcessing = false))
                } else {
                    scheduleBitmapRecycle(preview)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                publishLutEditor(editor.copy(intensity = normalized, isProcessing = false))
            }
        }
    }

    fun addLiveLut(preset: LutPreset) {
        _lutCaptureState.value = _lutCaptureState.value.addPreset(preset)
    }

    fun setBasicEdits(exposure: Float, contrast: Float, saturation: Float) {
        val editor = _lutEditor.value ?: return
        val updated = editor.copy(
            exposure = exposure.coerceIn(-1f, 1f),
            contrast = contrast.coerceIn(-1f, 1f),
            saturation = saturation.coerceIn(-1f, 1f),
            isProcessing = true,
        )
        lutJob?.cancel()
        publishLutEditor(updated)
        lutJob = viewModelScope.launch {
            try {
                val jpeg = readAssetBytes(editor.item.source)
                val preview = withContext(Dispatchers.Default) {
                    val imported = updated.importedLuts.firstOrNull { it.label == updated.selectedImportedLabel }
                    if (imported != null) imageProcessor.editPreview(
                        jpeg, imported.cube, updated.intensity,
                        updated.exposure, updated.contrast, updated.saturation,
                    ) else imageProcessor.editPreview(
                        jpeg, updated.preset, updated.intensity,
                        updated.exposure, updated.contrast, updated.saturation,
                    )
                }
                if (_lutEditor.value === updated) publishLutEditor(updated.copy(preview = preview, isProcessing = false))
                else scheduleBitmapRecycle(preview)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                publishLutEditor(updated.copy(isProcessing = false))
            }
        }
    }

    fun removeLiveLut(preset: LutPreset) {
        _lutCaptureState.value = _lutCaptureState.value.removePreset(preset)
        repository.latestFrame.value?.jpeg?.let { scheduleLutPreviewStrip(it, force = true) }
    }

    fun importCubeLut(name: String, text: String) {
        runCatching {
            val label = name.substringBeforeLast('.').ifBlank { "Imported LUT" }
            ImportedLut(label, CubeLut.parse(text))
        }.onSuccess { imported ->
            _lutCaptureState.value = _lutCaptureState.value.import(imported)
            viewModelScope.launch(Dispatchers.IO) {
                val encoded = Base64.encodeToString(
                    imported.label.toByteArray(),
                    Base64.URL_SAFE or Base64.NO_WRAP,
                )
                getApplication<Application>().getDir(IMPORTED_LUT_DIRECTORY, 0)
                    .resolve("$encoded.cube")
                    .writeText(text)
            }
            repository.latestFrame.value?.jpeg?.let { scheduleLutPreviewStrip(it, force = true) }
            _message.value = UiMessage("Imported ${imported.label}")
        }.onFailure { error ->
            _message.value = UiMessage(error.readableMessage("Could not import .cube LUT"), true)
        }
    }

    fun reportLutImportFailure(error: Throwable) {
        _message.value = UiMessage(error.readableMessage("Could not import .cube LUT"), true)
    }

    fun selectImportedLut(label: String) {
        _lutCaptureState.value = _lutCaptureState.value.selectImported(label)
        repository.latestFrame.value?.jpeg?.let { scheduleLutPreviewStrip(it, force = true) }
    }

    fun removeImportedLut(label: String) {
        _lutCaptureState.value.importedLuts.firstOrNull { it.label == label }
            ?.thumbnail?.let(::scheduleBitmapRecycle)
        _lutCaptureState.value = _lutCaptureState.value.removeImported(label)
        viewModelScope.launch(Dispatchers.IO) {
            val encoded = Base64.encodeToString(label.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            getApplication<Application>().getDir(IMPORTED_LUT_DIRECTORY, 0)
                .resolve("$encoded.cube")
                .delete()
        }
    }

    fun setBakeLutIntoPhotos(enabled: Boolean) {
        _lutCaptureState.value = _lutCaptureState.value.copy(bakeIntoPhotos = enabled)
    }

    fun saveLutCopy() {
        val editor = _lutEditor.value ?: return
        if (
            editor.isProcessing || _isBusy.value ||
            (editor.selectedImportedLabel == null && editor.preset == LutPreset.Neutral && editor.exposure == 0f &&
                editor.contrast == 0f && editor.saturation == 0f)
        ) return
        lutJob?.cancel()
        _isBusy.value = true
        publishLutEditor(editor.copy(isProcessing = true))
        lutJob = viewModelScope.launch {
            try {
                val source = readAssetBytes(editor.item.source)
                val processed = withContext(Dispatchers.Default) {
                    val imported = editor.importedLuts.firstOrNull { it.label == editor.selectedImportedLabel }
                    if (imported != null) imageProcessor.applyEditsToJpeg(
                        source, imported.cube, editor.intensity,
                        editor.exposure, editor.contrast, editor.saturation,
                    ) else imageProcessor.applyEditsToJpeg(
                        source, editor.preset, editor.intensity,
                        editor.exposure, editor.contrast, editor.saturation,
                    )
                }
                val saved = repository.saveProcessedJpeg(
                    jpeg = processed,
                    prefix = "SONY_LUT",
                    sourceCount = editor.item.sourceCount,
                    metadataSourceJpeg = source,
                )
                val lutLabel = editor.selectedImportedLabel ?: editor.preset.label
                appendSavedAsset(saved, CaptureAssetKind.Lut, lutLabel)
                publishLutEditor(null)
                _message.value = UiMessage("$lutLabel LUT copy saved")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                publishLutEditor(editor.copy(isProcessing = false))
                _message.value = UiMessage(error.readableMessage("Could not save the LUT copy"), true)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun closeLutEditor() {
        lutJob?.cancel()
        publishLutEditor(null)
    }

    private fun startLiveNd() {
        if (_isBusy.value || sessionJob?.isActive == true) return
        if (_liveNdStrategy.value == StackCaptureStrategy.ContinuousBurst) {
            val capabilities = (connection.value as? ConnectionState.Ready)?.capabilities
            val drive = capabilities?.settings?.get(CameraSettingId.ContShootingMode)
            val canPrepareBurst = capabilities?.canStartContinuousShooting == true ||
                drive?.isWritable == true && drive.options.any {
                    it.wireValue.equals("Continuous", ignoreCase = true)
                }
            if (canPrepareBurst) {
                startBurstLiveNd()
                return
            }
            _message.value = UiMessage("Burst capture is unavailable; using Single Live ND")
        }
        startSequentialLiveNd()
    }

    private fun startSequentialLiveNd() {
        val targetFrames = _liveNdFrames.value
        startAutomaticSession(
            mode = CaptureMode.LiveNd,
            targetFrames = targetFrames,
            outputPrefix = "SONY_LIVE_ND",
            outputKind = CaptureAssetKind.LiveNd,
            outputTitle = "Live ND",
            createProcessor = { ComputationalImageEngine().newLiveNdSession() },
            addFrame = LiveNdSession::add,
            encode = LiveNdSession::encodeJpeg,
        )
    }

    private fun startBurstLiveNd() {
        if (_isBusy.value || sessionJob?.isActive == true) return
        val targetFrames = _liveNdFrames.value
        pauseOriginalImportForCameraWork()
        _isBusy.value = true
        sessionStopRequested = false
        publishCaptureSession(CaptureSessionUiState(
            mode = CaptureMode.LiveNd,
            isActive = true,
            targetFrames = targetFrames,
        ))
        sessionJob = viewModelScope.launch {
            var processor: LiveNdSession? = null
            var frames = 0
            var attempts = 0
            var metadataSource: ByteArray? = null
            val burstSourceIds = mutableListOf<String>()
            val frameTracker = LiveNdCaptureTracker(targetFrames)
            var fallbackToSequential = false
            val sessionDeadlineNanos =
                System.nanoTime() + MAX_LIVE_ND_SESSION_MILLIS * NANOS_PER_MILLI
            val batchChannel = Channel<ContinuousBatchResult>(capacity = MAX_LIVE_ND_BURST_BATCHES)
            liveNdBatchResults = batchChannel
            repository.setComputationalCaptureImport(true)
            try {
                pauseLiveViewForSession()
                ensureContinuousDriveForLiveNd()
                repository.setAutomaticPostviewPreference(_automaticPostviewPreference.value)
                processor = withContext(Dispatchers.Default) {
                    ComputationalImageEngine().newLiveNdSession()
                }
                suspend fun acceptBatch(batch: ContinuousBatchResult) {
                    batch.importedCaptures.forEach { capture ->
                        if (frameTracker.disposition() == LiveNdFrameDisposition.Extra) {
                            appendSavedAsset(capture, CaptureAssetKind.Photo, "ND extra")
                            return@forEach
                        }
                        val preview = try {
                            withContext(Dispatchers.Default) { requireNotNull(processor).add(capture.jpeg) }
                        } catch (error: FrameAlignmentException) {
                            frameTracker.recordRejected()
                            appendSavedAsset(capture, CaptureAssetKind.Photo, "ND rejected")
                            _message.value = UiMessage(
                                error.readableMessage("This burst frame could not be aligned"),
                                true,
                            )
                            return@forEach
                        }
                        frameTracker.recordAccepted()
                        frames = frameTracker.acceptedFrames
                        if (metadataSource == null) metadataSource = capture.jpeg.copyOf()
                        burstSourceIds += appendSavedAsset(
                            capture,
                            CaptureAssetKind.SourceFrame,
                            "ND $frames",
                        ).id
                        publishCaptureSession(_captureSession.value.copy(
                            frameCount = frames,
                            preview = preview,
                        ))
                    }
                }
                while (
                    frames < targetFrames &&
                    attempts < MAX_LIVE_ND_RETRY_BURSTS &&
                    frameTracker.consecutiveAlignmentFailures < MAX_CONSECUTIVE_ALIGNMENT_FAILURES &&
                    System.nanoTime() < sessionDeadlineNanos &&
                    !sessionStopRequested
                ) {
                    attempts++
                    val rateKey = currentBurstRateKey()
                    val requestedMillis = liveNdBurstDurationMillis(
                        remainingFrames = targetFrames - frames,
                        key = rateKey,
                        firstBurst = attempts == 1,
                    )
                    var startCompletedNanos = 0L
                    var stopStartedNanos = 0L
                    var stopCompletedNanos = 0L
                    startContinuousShootingForLiveNd()
                    startCompletedNanos = System.nanoTime()
                    try {
                        val deadline = System.nanoTime() + requestedMillis * NANOS_PER_MILLI
                        while (!sessionStopRequested && System.nanoTime() < deadline) delay(BURST_STOP_CHECK_MILLIS)
                    } finally {
                        stopStartedNanos = System.nanoTime()
                        withContext(NonCancellable) {
                            runCatching { repository.stopContinuousShooting() }
                        }
                        stopCompletedNanos = System.nanoTime()
                    }
                    val firstBatch = withTimeout(LIVE_ND_BATCH_EVENT_TIMEOUT_MILLIS) {
                        batchChannel.receive()
                    }
                    var importedThisBurst = firstBatch.importedCaptures.size
                    acceptBatch(firstBatch)
                    while (!sessionStopRequested) {
                        withTimeout(LIVE_ND_BURST_TRANSFER_TIMEOUT_MILLIS) {
                            repository.isPhysicalShutterTransferActive.first { active -> !active }
                        }
                        val additionalBatch = withTimeoutOrNull(LIVE_ND_BATCH_QUIET_MILLIS) {
                            batchChannel.receive()
                        } ?: break
                        importedThisBurst += additionalBatch.importedCaptures.size
                        acceptBatch(additionalBatch)
                    }
                    if (startCompletedNanos > 0L && stopStartedNanos >= startCompletedNanos) {
                        burstRateEstimator.record(
                            key = rateKey,
                            commandHoldMillis =
                                (stopStartedNanos - startCompletedNanos) / NANOS_PER_MILLI,
                            stopCommandLatencyMillis =
                                (stopCompletedNanos - stopStartedNanos) / NANOS_PER_MILLI,
                            importedFrameCount = importedThisBurst,
                        )
                    }
                }
                if (frames >= targetFrames) {
                    publishCaptureSession(_captureSession.value.copy(isFinishing = true))
                    val jpeg = withContext(Dispatchers.Default) { requireNotNull(processor).encodeJpeg() }
                    val saved = repository.saveProcessedJpeg(
                        jpeg = jpeg,
                        prefix = "SONY_LIVE_ND",
                        sourceCount = frames,
                        metadataSourceJpeg = metadataSource,
                    )
                    appendSavedAsset(
                        saved,
                        CaptureAssetKind.LiveNd,
                        "Live ND",
                        sourceCount = frames,
                        relatedSourceIds = burstSourceIds.toList(),
                    )
                    _message.value = UiMessage("Live ND saved from $frames burst frames")
                } else if (!sessionStopRequested) {
                    fallbackToSequential = true
                    _message.value = UiMessage(
                        "Burst capture was unreliable; switching to Single Live ND",
                        true,
                    )
                }
            } catch (error: TimeoutCancellationException) {
                if (connection.value is ConnectionState.Ready && !sessionStopRequested) {
                    fallbackToSequential = true
                    _message.value = UiMessage(
                        "Burst capture timed out; switching to Single Live ND",
                        true,
                    )
                } else {
                    throw error
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (connection.value is ConnectionState.Ready && !sessionStopRequested) {
                    fallbackToSequential = true
                    _message.value = UiMessage(
                        error.readableMessage("Burst capture failed; switching to Single Live ND"),
                        true,
                    )
                } else {
                    _message.value = UiMessage(error.readableMessage("Burst Live ND stopped"), true)
                }
            } finally {
                withContext(NonCancellable) {
                    liveNdBatchResults = null
                    repository.setComputationalCaptureImport(false)
                    batchChannel.close()
                    runCatching { repository.stopContinuousShooting() }
                    withContext(Dispatchers.Default) { runCatching { processor?.close() } }
                    withTimeoutOrNull(POSTVIEW_RESTORE_TIMEOUT_MILLIS) {
                        runCatching { repository.restoreOriginalPostview() }
                    }
                    publishCaptureSession(_captureSession.value.copy(
                        isActive = false,
                        isFinishing = false,
                        frameCount = frames,
                    ))
                    _isBusy.value = false
                    if (!isDisconnecting) {
                        withTimeoutOrNull(LIVEVIEW_RESUME_TIMEOUT_MILLIS) {
                            resumeLiveViewAfterSession()
                        }
                    }
                }
            }
            if (fallbackToSequential && connection.value is ConnectionState.Ready) {
                val completedBurstJob = currentCoroutineContext()[Job]
                viewModelScope.launch {
                    completedBurstJob?.join()
                    if (!isDisconnecting && !sessionStopRequested) startSequentialLiveNd()
                }
            }
        }
    }

    private suspend fun ensureContinuousDriveForLiveNd(): String? {
        var ready = connection.value as? ConnectionState.Ready
            ?: throw IOException("Connect to the camera before starting Live ND")
        var drive = ready.capabilities.settings[CameraSettingId.ContShootingMode]
        val previousDrive = drive?.currentWireValue
        if (drive?.currentWireValue.equals("Single", ignoreCase = true)) {
            val continuous = drive?.options?.firstOrNull {
                it.wireValue.equals("Continuous", ignoreCase = true)
            }
            if (drive?.isWritable != true || continuous == null) {
                throw IOException("This camera does not offer continuous shooting for Live ND")
            }
            repository.setSetting(CameraSettingId.ContShootingMode, continuous.wireValue)
            repository.refreshCapabilities()
            ready = connection.value as? ConnectionState.Ready
                ?: throw IOException("Camera connection ended while preparing burst Live ND")
            drive = ready.capabilities.settings[CameraSettingId.ContShootingMode]
        }
        check(ready.capabilities.canStartContinuousShooting) {
            "The camera did not expose continuous shooting after changing Drive mode"
        }
        check(!drive?.currentWireValue.equals("Single", ignoreCase = true)) {
            "The camera did not enter continuous Drive mode"
        }
        highestBurstSpeed(ready.capabilities.settings[CameraSettingId.ContShootingSpeed])?.let { fastest ->
            val speed = (connection.value as? ConnectionState.Ready)
                ?.capabilities?.settings?.get(CameraSettingId.ContShootingSpeed)
            if (speed?.isWritable == true && speed.currentWireValue != fastest) {
                repository.setSetting(CameraSettingId.ContShootingSpeed, fastest)
            }
        }
        return previousDrive
    }

    private suspend fun restoreDriveAfterLiveNd(previousDrive: String?) {
        if (previousDrive == null) return
        val current = (connection.value as? ConnectionState.Ready)
            ?.capabilities
            ?.settings
            ?.get(CameraSettingId.ContShootingMode)
            ?.currentWireValue
        if (!current.equals(previousDrive, ignoreCase = true)) {
            runCatching { repository.setSetting(CameraSettingId.ContShootingMode, previousDrive) }
        }
    }

    private fun currentBurstRateKey(): BurstRateKey {
        val ready = connection.value as? ConnectionState.Ready
        return BurstRateKey(
            cameraModel = ready?.device?.modelName ?: "unknown",
            continuousSpeed = ready?.capabilities?.settings
                ?.get(CameraSettingId.ContShootingSpeed)
                ?.currentWireValue,
            shutterSpeed = ready?.capabilities?.settings
                ?.get(CameraSettingId.ShutterSpeed)
                ?.currentWireValue,
        )
    }

    private suspend fun startContinuousShootingForLiveNd() {
        var lastError: Throwable? = null
        repeat(LIVE_ND_START_RETRY_ATTEMPTS) { attempt ->
            try {
                repository.startContinuousShooting()
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                if (attempt + 1 < LIVE_ND_START_RETRY_ATTEMPTS) {
                    delay(LIVE_ND_START_RETRY_DELAY_MILLIS)
                    repository.refreshCapabilities()
                }
            }
        }
        throw IOException(
            lastError?.readableMessage("Continuous shooting did not become available")
                ?: "Continuous shooting did not become available",
            lastError,
        )
    }

    private fun liveNdBurstDurationMillis(
        remainingFrames: Int,
        key: BurstRateKey,
        firstBurst: Boolean,
    ): Long {
        val exposureOnlyHold = shutterDurationSeconds(key.shutterSpeed)
            ?.times(remainingFrames + 1)
            ?.times(1_000.0)
            ?.toLong()
        if (firstBurst && exposureOnlyHold != null) {
            return exposureOnlyHold.coerceIn(MIN_INITIAL_LIVE_ND_BURST_MILLIS, MAX_LIVE_ND_BURST_MILLIS)
        }
        return burstRateEstimator.estimatedHoldMillis(
            key = key,
            requestedFrames = remainingFrames,
            fallbackFrameIntervalMillis = LIVE_ND_ESTIMATED_FRAME_MILLIS,
        )
            .coerceIn(MIN_LIVE_ND_BURST_MILLIS, MAX_LIVE_ND_BURST_MILLIS)
    }

    private fun startLiveComposite() {
        if (_isBusy.value || sessionJob?.isActive == true) return
        startAutomaticSession(
            mode = CaptureMode.LiveComposite,
            targetFrames = null,
            outputPrefix = "SONY_COMPOSITE",
            outputKind = CaptureAssetKind.LiveComposite,
            outputTitle = "Composite",
            createProcessor = { ComputationalImageEngine().newLiveCompositeSession() },
            addFrame = LiveCompositeSession::add,
            encode = LiveCompositeSession::encodeJpeg,
        )
    }

    private fun <T : AutoCloseable> startAutomaticSession(
        mode: CaptureMode,
        targetFrames: Int?,
        outputPrefix: String,
        outputKind: CaptureAssetKind,
        outputTitle: String,
        createProcessor: () -> T,
        addFrame: T.(ByteArray) -> Bitmap,
        encode: T.() -> ByteArray,
    ) {
        if (_isBusy.value || sessionJob?.isActive == true) return
        pauseOriginalImportForCameraWork()
        _isBusy.value = true
        sessionStopRequested = false
        publishCaptureSession(CaptureSessionUiState(
            mode = mode,
            isActive = true,
            targetFrames = targetFrames,
        ))
        sessionJob = viewModelScope.launch {
            var processor: T? = null
            var frames = 0
            var attempts = 0
            var metadataSource: ByteArray? = null
            var previousDrive: String? = null
            val sourceIds = mutableListOf<String>()
            try {
                pauseLiveViewForSession()
                previousDrive = ensureSingleDriveForComputationalCapture()
                processor = withContext(Dispatchers.Default) { createProcessor() }
                val maximum = targetFrames ?: MAX_COMPOSITE_FRAMES
                val maximumAttempts = maximum * MAX_ALIGNMENT_ATTEMPT_MULTIPLIER
                while (frames < maximum && attempts < maximumAttempts && !sessionStopRequested) {
                    attempts++
                    val capture = repository.downloadCapture(_automaticPostviewPreference.value)
                    val savedSource = repository.saveCapture(capture, "SONY_SOURCE")
                    val source = appendSavedAsset(
                        savedSource,
                        CaptureAssetKind.SourceFrame,
                        "${mode.label} source ${frames + 1}",
                    )
                    sourceIds += source.id
                    val preview = try {
                        withContext(Dispatchers.Default) { processor.addFrame(capture.jpeg) }
                    } catch (error: FrameAlignmentException) {
                        removeWorkspaceAsset(source)
                        _message.value = UiMessage(error.readableMessage("This frame could not be aligned"), true)
                        continue
                    }
                    frames++
                    if (metadataSource == null) metadataSource = capture.jpeg.copyOf()
                    publishCaptureSession(_captureSession.value.copy(
                        frameCount = frames,
                        preview = preview,
                    ))
                }
                if (frames >= MIN_COMPUTATIONAL_FRAMES) {
                    publishCaptureSession(_captureSession.value.copy(isFinishing = true))
                    val jpeg = withContext(Dispatchers.Default) { processor.encode() }
                    val saved = repository.saveProcessedJpeg(
                        jpeg,
                        outputPrefix,
                        frames,
                        metadataSourceJpeg = metadataSource,
                    )
                    appendSavedAsset(
                        saved,
                        outputKind,
                        outputTitle,
                        sourceCount = frames,
                        relatedSourceIds = sourceIds.toList(),
                    )
                    _message.value = UiMessage("$outputTitle saved from $frames frames")
                } else {
                    _message.value = UiMessage("$outputTitle needs at least two frames", true)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(error.readableMessage("$outputTitle capture failed"), true)
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Default) { runCatching { processor?.close() } }
                    withTimeoutOrNull(POSTVIEW_RESTORE_TIMEOUT_MILLIS) {
                        runCatching { repository.restoreOriginalPostview() }
                    }
                    restoreDriveSetting(previousDrive)
                    publishCaptureSession(_captureSession.value.copy(
                        isActive = false,
                        isFinishing = false,
                        frameCount = frames,
                    ))
                    _isBusy.value = false
                    if (!isDisconnecting) {
                        withTimeoutOrNull(LIVEVIEW_RESUME_TIMEOUT_MILLIS) {
                            resumeLiveViewAfterSession()
                        }
                    }
                }
            }
        }
    }

    private fun requestComputationalStop() {
        if (!_captureSession.value.isActive) return
        sessionStopRequested = true
        publishCaptureSession(_captureSession.value.copy(isFinishing = true))
    }

    private fun startPanorama() {
        if (_isBusy.value || panoramaSession != null) return
        pauseOriginalImportForCameraWork()
        runCommand {
            var processor: PanoramaSession? = null
            var postviewPreparationAttempted = false
            try {
                panoramaPreviousDrive = ensureSingleDriveForComputationalCapture()
                panoramaFrameLimit = panoramaFrameLimitForMemory(Runtime.getRuntime().maxMemory())
                processor = ComputationalImageEngine().newPanoramaSession(panoramaFrameLimit)
                postviewPreparationAttempted = true
                repository.setAutomaticPostviewPreference(_automaticPostviewPreference.value)
                repository.setComputationalCaptureImport(true)
                panoramaSession = processor
                panoramaMetadataSource = null
                panoramaSourceItems.clear()
                publishCaptureSession(CaptureSessionUiState(
                    mode = CaptureMode.Panorama,
                    isActive = true,
                    targetFrames = panoramaFrameLimit,
                ))
            } catch (error: Throwable) {
                synchronized(panoramaProcessorLock) { processor?.close() }
                repository.setComputationalCaptureImport(false)
                if (postviewPreparationAttempted) restorePanoramaSettingsBestEffort()
                if (error is CancellationException) throw error
                throw IOException(error.readableMessage("Panorama could not start"), error)
            }
        }
    }

    private suspend fun restoreOriginalPostviewBestEffort(): Throwable? =
        withContext(NonCancellable) {
            var failure: Throwable? = null
            val completed = withTimeoutOrNull(POSTVIEW_RESTORE_TIMEOUT_MILLIS) {
                failure = runCatching { repository.restoreOriginalPostview() }.exceptionOrNull()
                true
            } == true
            if (completed) failure else IOException("Timed out restoring Original postview size")
        }

    private suspend fun renderFinalPanorama(processor: PanoramaSession): Pair<ByteArray, Boolean> {
        val sources = panoramaSourceItems.toList()
        val geometry = synchronized(panoramaProcessorLock) { processor.geometry() }
        if (sources.size != geometry.size || sources.size < 2) {
            return withContext(Dispatchers.Default) {
                synchronized(panoramaProcessorLock) { processor.encodeJpeg() }
            } to false
        }
        return runCatching {
            val dimensions = sources.mapIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                publishCaptureSession(_captureSession.value.copy(
                    processingProgress = index.toFloat() / (sources.size * 2),
                ))
                val bytes = readAssetBytes(item.source)
                withContext(Dispatchers.Default) { jpegDimensions(bytes) }
            }
            val cacheDir = getApplication<Application>().cacheDir
            val plan = PanoramaRenderPlanner.plan(
                geometry = geometry,
                sources = dimensions,
                budget = PanoramaResourceBudget(
                    maxHeapBytes = Runtime.getRuntime().maxMemory(),
                    availableDiskBytes = cacheDir.usableSpace.coerceAtLeast(1L),
                ),
            )
            PanoramaFinalRenderer(geometry, plan, imageProcessor).use { renderer ->
                sources.forEachIndexed { index, item ->
                    currentCoroutineContext().ensureActive()
                    val bytes = readAssetBytes(item.source)
                    withContext(Dispatchers.Default) {
                        renderer.addSource(index, bytes, dimensions[index])
                    }
                    publishCaptureSession(_captureSession.value.copy(
                        processingProgress = (sources.size + index + 1).toFloat() / (sources.size * 2),
                    ))
                }
                withContext(Dispatchers.Default) { renderer.encodeJpeg() } to plan.originalResolution
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            _message.value = UiMessage(
                error.readableMessage("High-resolution render unavailable; saving panorama preview"),
                true,
            )
            withContext(Dispatchers.Default) {
                synchronized(panoramaProcessorLock) { processor.encodeJpeg() }
            } to false
        }
    }

    private fun jpegDimensions(jpeg: ByteArray): PanoramaSourceDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        check(options.outWidth > 0 && options.outHeight > 0) { "Could not read panorama source size" }
        val rotation = runCatching {
            ExifInterface(ByteArrayInputStream(jpeg)).rotationDegrees
        }.getOrDefault(0)
        return if (rotation == 90 || rotation == 270) {
            PanoramaSourceDimensions(options.outHeight, options.outWidth)
        } else {
            PanoramaSourceDimensions(options.outWidth, options.outHeight)
        }
    }

    private suspend fun restorePanoramaSettingsBestEffort(): Throwable? {
        val postviewFailure = restoreOriginalPostviewBestEffort()
        val driveFailure = restorePanoramaDriveBestEffort()
        return postviewFailure ?: driveFailure
    }

    private suspend fun restorePanoramaDriveBestEffort(): Throwable? {
        val previous = panoramaPreviousDrive ?: return null
        panoramaPreviousDrive = null
        val current = (connection.value as? ConnectionState.Ready)
            ?.capabilities
            ?.settings
            ?.get(CameraSettingId.ContShootingMode)
            ?.currentWireValue
        if (current.equals(previous, ignoreCase = true)) return null
        return runCatching {
            withTimeout(POSTVIEW_RESTORE_TIMEOUT_MILLIS) {
                repository.setSetting(CameraSettingId.ContShootingMode, previous)
            }
        }.exceptionOrNull()
    }

    private fun capturePanoramaFrame() {
        val processor = panoramaSession ?: return
        if (_isBusy.value) return
        if (processor.frameCount >= panoramaFrameLimit) {
            _message.value = UiMessage("Finish this panorama before adding more frames")
            return
        }
        runCommand {
            val capture = repository.downloadCapture(_automaticPostviewPreference.value)
            val frameNumber = processor.frameCount + 1
            val savedSource = repository.saveCapture(capture, "SONY_PANO_SOURCE")
            val source = appendSavedAsset(savedSource, CaptureAssetKind.SourceFrame, "Pano source $frameNumber")
            try {
                val preview = withContext(Dispatchers.Default) {
                    synchronized(panoramaProcessorLock) { processor.add(capture.jpeg) }
                }
                if (panoramaMetadataSource == null) panoramaMetadataSource = capture.jpeg.copyOf()
                panoramaSourceItems += source
                publishCaptureSession(_captureSession.value.copy(
                    frameCount = processor.frameCount,
                    preview = preview,
                ))
            } catch (error: Throwable) {
                _message.value = UiMessage(error.readableMessage("This panorama frame could not be aligned"), true)
            }
        }
    }

    private suspend fun ensureSingleDriveForComputationalCapture(): String? {
        var ready = connection.value as? ConnectionState.Ready
            ?: throw IOException("Connect to the camera before starting panorama")
        var drive = ready.capabilities.settings[CameraSettingId.ContShootingMode]
        val previousDrive = drive?.currentWireValue
        if (drive == null) {
            if (ready.capabilities.canTakePicture) return null
            throw IOException("Set the camera Drive mode to Single before starting panorama")
        }
        if (!drive.currentWireValue.equals("Single", ignoreCase = true)) {
            val single = drive.options.firstOrNull {
                it.wireValue.equals("Single", ignoreCase = true)
            }
            if (!drive.isWritable || single == null) {
                throw IOException("Set the camera Drive mode to Single before starting panorama")
            }
            repository.setSetting(CameraSettingId.ContShootingMode, single.wireValue)
            ready = connection.value as? ConnectionState.Ready
                ?: throw IOException("Camera connection ended while selecting Single drive")
            drive = ready.capabilities.settings[CameraSettingId.ContShootingMode]
        }
        if (!ready.capabilities.canTakePicture) {
            repository.refreshCapabilities()
            ready = connection.value as? ConnectionState.Ready
                ?: throw IOException("Camera connection ended while preparing panorama")
            drive = ready.capabilities.settings[CameraSettingId.ContShootingMode]
        }
        if (!drive?.currentWireValue.equals("Single", ignoreCase = true) ||
            !ready.capabilities.canTakePicture
        ) {
            throw IOException("Camera did not enter Single drive mode for panorama")
        }
        return previousDrive
    }

    private suspend fun restoreDriveSetting(previousDrive: String?) {
        if (previousDrive == null) return
        val current = (connection.value as? ConnectionState.Ready)
            ?.capabilities
            ?.settings
            ?.get(CameraSettingId.ContShootingMode)
            ?.currentWireValue
        if (!current.equals(previousDrive, ignoreCase = true)) {
            runCatching { repository.setSetting(CameraSettingId.ContShootingMode, previousDrive) }
        }
    }

    private suspend fun handlePhysicalShutterCapture(capture: SavedCapture) {
        if (isDisconnecting) return
        val processor = panoramaSession
        val shouldRouteToPanorama = shouldRoutePhysicalCaptureToPanorama(
            selectedMode = _captureMode.value,
            session = _captureSession.value,
            hasProcessor = processor != null,
            isBusy = _isBusy.value,
            processorFrameCount = processor?.frameCount ?: 0,
            maximumFrames = panoramaFrameLimit,
        )
        if (!shouldRouteToPanorama || processor == null) {
            appendPhysicalCameraCapture(capture)
            return
        }

        _isBusy.value = true
        var pendingPreview: Bitmap? = null
        try {
            try {
                withContext(NonCancellable) {
                    pendingPreview = withContext(Dispatchers.Default) {
                        synchronized(panoramaProcessorLock) { processor.add(capture.jpeg) }
                    }
                }
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                retainRejectedPhysicalCapture(capture, error)
                return
            }

            if (isDisconnecting || panoramaSession !== processor || !_captureSession.value.isActive) return
            val frameNumber = processor.frameCount
            if (panoramaMetadataSource == null) panoramaMetadataSource = capture.jpeg.copyOf()
            publishCaptureSession(_captureSession.value.copy(
                frameCount = frameNumber,
                preview = requireNotNull(pendingPreview),
            ))
            pendingPreview = null
            try {
                val sourceItem = appendSavedAsset(
                    capture = capture,
                    kind = CaptureAssetKind.SourceFrame,
                    title = "Pano $frameNumber",
                )
                panoramaSourceItems += sourceItem
                _message.value = UiMessage("Camera shutter frame $frameNumber added to panorama")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(
                    error.readableMessage(
                        "Panorama frame $frameNumber was added but could not be shown in the filmstrip",
                    ),
                    true,
                )
            }
        } finally {
            pendingPreview?.let { preview ->
                if (!preview.isRecycled) preview.recycle()
            }
            _isBusy.value = false
        }
    }

    private suspend fun appendPhysicalCameraCapture(capture: SavedCapture) {
        try {
            if (!replacePendingPhysicalCapture(capture)) {
                appendSavedAsset(capture, CaptureAssetKind.Photo, "Camera")
            }
            _message.value = UiMessage("Camera shutter photo saved to Pictures/Sony Remote")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _message.value = UiMessage(
                error.readableMessage(
                    "Camera shutter photo was saved but could not be added to the filmstrip",
                ),
                true,
            )
        }
    }

    private suspend fun retainRejectedPhysicalCapture(
        capture: SavedCapture,
        alignmentError: Throwable,
    ) {
        val alignmentMessage = alignmentError.readableMessage("This panorama frame could not be aligned")
        try {
            appendSavedAsset(capture, CaptureAssetKind.Photo, "Camera")
            _message.value = UiMessage("$alignmentMessage. Photo kept as Camera.", true)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _message.value = UiMessage(
                "$alignmentMessage. Photo is saved in Pictures/Sony Remote but could not be shown in the filmstrip.",
                true,
            )
        }
    }

    fun setSetting(id: CameraSettingId, wireValue: String) = runCommand {
        repository.setSetting(id, wireValue)
    }

    fun startRemoteZoom(direction: String) = runCommand {
        repository.startZoom(direction)
    }

    fun stopRemoteZoom(direction: String) = runCommand {
        repository.stopZoom(direction)
    }

    fun refreshZoomState() = runCommand { repository.refreshZoomState() }

    fun setRemoteZoomTarget(targetPercent: Int) {
        val target = targetPercent.coerceIn(0, 100)
        val ready = connection.value as? ConnectionState.Ready ?: return
        val currentPosition = ready.capabilities.zoomPosition
        if (currentPosition == null) {
            _message.value = UiMessage("This camera does not report zoom position", true)
            return
        }
        val initialPosition: Int = currentPosition
        if (kotlin.math.abs(initialPosition - target) <= ZOOM_TARGET_TOLERANCE) return
        zoomTargetJob?.cancel()
        _zoomTargetPercent.value = target
        zoomTargetJob = viewModelScope.launch {
            val direction = if (target > initialPosition) "in" else "out"
            try {
                withTimeout(ZOOM_TARGET_TIMEOUT_MILLIS) {
                    var position = initialPosition
                    var steps = 0
                    var stalledAttempts = 0
                    while (!zoomTargetReached(position, target, direction, ZOOM_TARGET_TOLERANCE)) {
                        check(steps++ < ZOOM_TARGET_MAX_STEPS) { "Zoom target did not converge" }
                        val previous = position
                        position = repository.stepZoomAndReadPosition(direction)
                        if (position == previous) {
                            stalledAttempts++
                            check(stalledAttempts < ZOOM_STALLED_RETRY_LIMIT) {
                                "Zoom did not move after $ZOOM_STALLED_RETRY_LIMIT attempts"
                            }
                            delay(ZOOM_STALLED_RETRY_DELAY_MILLIS)
                        } else {
                            stalledAttempts = 0
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    _message.value = UiMessage(error.readableMessage("Could not reach zoom target"), true)
                }
            } finally {
                withContext(NonCancellable) { runCatching { repository.stopZoom(direction) } }
                _zoomTargetPercent.value = null
            }
        }
    }

    fun toggleMovieRecording() = runCommand {
        val result = repository.toggleMovieRecording()
        _message.value = UiMessage(
            when (result) {
                MovieActionResult.Started -> "Movie recording started"
                MovieActionResult.Stopped -> "Movie recording stopped"
                MovieActionResult.AlreadyRecording -> "Camera is already recording"
                MovieActionResult.AlreadyStopped -> "Camera is already idle"
            },
        )
    }

    fun retryLiveView() {
        if (!isForeground || liveViewJob?.isActive == true) return
        if (liveViewRestartJob?.isActive == true) return
        liveViewRestartJob = viewModelScope.launch {
            liveViewStartupFailures = 0
            liveViewJob?.cancel()
            repository.cancelLiveViewIo()
            liveViewJob?.join()
            try {
                if (repository.awaitLiveViewAvailability()) startLiveView()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(error.readableMessage("Live view is unavailable"), true)
            }
        }
    }

    fun setLiveviewSize(size: String) {
        liveViewRestartJob?.cancel()
        viewModelScope.launch {
            _isBusy.value = true
            try {
                liveViewJob?.cancel()
                repository.cancelLiveViewIo()
                liveViewJob?.join()
                repository.awaitLiveViewAvailability()
                repository.setLiveviewSize(size)
                if (isForeground) startLiveView()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(error.readableMessage("Could not change live-view quality"), true)
                if (isForeground) retryLiveView()
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun onNearbyPermissionResult(granted: Boolean) {
        if (granted) {
            _permissionBlocked.value = false
            connect()
        } else {
            _permissionBlocked.value = true
            _message.value = UiMessage(
                "Nearby devices access is required for camera discovery on Android 16",
                isError = true,
            )
        }
    }

    fun onForeground() {
        if (
            Build.VERSION.SDK_INT < 36 ||
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            _permissionBlocked.value = false
        }
        if (isForeground) return
        isForeground = true
        if (isDisconnecting) return
        if (sessionJob?.isActive == true) return
        val previous = lifecycleJob
        lifecycleJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            liveViewJob?.cancel()
            repository.cancelLiveViewIo()
            liveViewJob?.join()
            val ready = connection.value as? ConnectionState.Ready ?: return@launch
            val hadLiveViewControl = ready.capabilities.canLiveView ||
                "stopLiveview" in ready.capabilities.availableApis
            try {
                if (hadLiveViewControl && repository.awaitLiveViewAvailability()) startLiveView()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(error.readableMessage("Live view is unavailable"), true)
            } finally {
                if (!isDisconnecting && connection.value is ConnectionState.Ready) {
                    startStatePolling()
                }
            }
        }
    }

    fun onBackground() {
        if (!isForeground) return
        isForeground = false
        pauseOriginalImportForCameraWork()
        val previous = lifecycleJob
        previous?.cancel()
        liveViewRestartJob?.cancel()
        continuousBurstJob?.cancel()
        zoomTargetJob?.cancel()
        lutPreviewJob?.cancel()
        lutDerivativeJobs.values.forEach(Job::cancel)
        lutDerivativeJobs.clear()
        liveViewJob?.cancel()
        pollJob?.cancel()
        if (sessionJob?.isActive == true) {
            sessionStopRequested = true
            repository.cancelLiveViewIo()
        } else if (commandJob?.isActive == true) {
            repository.cancelLiveViewIo()
        } else {
            repository.cancelActiveIo()
        }
        lifecycleJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            liveViewRestartJob?.cancelAndJoin()
            continuousBurstJob?.cancelAndJoin()
            liveViewJob?.cancelAndJoin()
            pollJob?.cancelAndJoin()
        }
    }

    private fun startLiveView() {
        if (!isForeground || isDisconnecting) return
        val streamingJob = viewModelScope.launch {
            try {
                repository.streamLiveView()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(error.readableMessage("Live view stopped"), true)
            }
        }
        liveViewJob = streamingJob
        liveViewRestartJob?.cancel()
        liveViewRestartJob = viewModelScope.launch {
            delay(LIVEVIEW_FIRST_FRAME_TIMEOUT_MILLIS)
            if (
                _previewBitmap.value == null &&
                streamingJob.isActive &&
                isForeground &&
                !isDisconnecting
            ) {
                liveViewStartupFailures++
                repository.cancelLiveViewIo()
                streamingJob.cancelAndJoin()
                if (liveViewStartupFailures < MAX_LIVEVIEW_START_ATTEMPTS) {
                    delay(LIVEVIEW_RETRY_DELAY_MILLIS)
                    if (repository.awaitLiveViewAvailability()) startLiveView()
                } else {
                    _message.value = UiMessage("Live view did not produce frames; tap Start live view to retry", true)
                }
            }
        }
    }

    private fun runCommand(successMessage: String? = null, command: suspend () -> Unit) {
        if (_isBusy.value || _isContinuousBurstActive.value) return
        _isBusy.value = true
        commandJob = viewModelScope.launch {
            try {
                command()
                if (successMessage != null) _message.value = UiMessage(successMessage)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(error.readableMessage("Camera command failed"), true)
            } finally {
                _isBusy.value = false
            }
        }
    }

    private suspend fun appendSourceAsset(
        capture: DownloadedCapture,
        mode: CaptureMode,
        frameNumber: Int,
    ): FilmstripItem {
        val workspaceItem = workspace.writeJpeg(capture.jpeg)
        val thumbnail = withContext(Dispatchers.Default) { imageProcessor.thumbnail(capture.jpeg) }
        val item = FilmstripItem(
            kind = CaptureAssetKind.SourceFrame,
            title = when (mode) {
                CaptureMode.LiveNd -> "ND $frameNumber"
                CaptureMode.LiveComposite -> "Comp $frameNumber"
                CaptureMode.Panorama -> "Pano $frameNumber"
                CaptureMode.Photo -> "Photo"
            },
            source = CaptureAssetSource.Workspace(workspaceItem),
            thumbnail = thumbnail,
        )
        appendFilmstrip(item)
        return item
    }

    private suspend fun appendSavedAsset(
        capture: SavedCapture,
        kind: CaptureAssetKind,
        title: String,
        sourceCount: Int = 1,
        relatedSourceIds: List<String> = emptyList(),
    ): FilmstripItem {
        var pendingThumbnail: Bitmap? = null
        return try {
            val lutState = _lutCaptureState.value
            val appliedLutName = if (lutState.bakeIntoPhotos && !lutState.isOriginal) {
                lutState.importedLut?.label ?: lutState.preset.label
            } else {
                null
            }
            val appliedLutStrength = lutState.intensity.takeIf { appliedLutName != null }
            if (appliedLutName != null && appliedLutStrength != null) {
                runCatching {
                    repository.setLutAttribution(capture.uri, appliedLutName, appliedLutStrength)
                }
            }
            withContext(NonCancellable) {
                pendingThumbnail = withContext(Dispatchers.Default) {
                    imageProcessor.thumbnail(capture.jpeg)
                }
            }
            currentCoroutineContext().ensureActive()
            val itemId = UUID.randomUUID().toString()
            val item = FilmstripItem(
                id = itemId,
                kind = kind,
                title = title,
                source = CaptureAssetSource.MediaStore(capture.uri),
                thumbnail = requireNotNull(pendingThumbnail),
                sourceCount = sourceCount,
                importedCapture = if (kind == CaptureAssetKind.Photo) {
                    ImportedCapture(
                        id = itemId,
                        previewUri = capture.uri.takeUnless { capture.originalSizeRequested },
                        originalUri = capture.uri.takeIf { capture.originalSizeRequested },
                        thumbnailRemoteUrl = capture.thumbnailRemoteUri,
                        postviewRemoteUrl = capture.postviewRemoteUri,
                        postviewIsOriginal = capture.postviewRemoteIsOriginal,
                        cameraContentId = capture.cameraContentId,
                    )
                } else {
                    null
                },
                relatedSourceIds = relatedSourceIds,
                appliedLutName = appliedLutName,
                appliedLutStrength = appliedLutStrength,
            )
            if (kind in setOf(
                    CaptureAssetKind.LiveNd,
                    CaptureAssetKind.LiveComposite,
                    CaptureAssetKind.Panorama,
                )
            ) {
                val orphaned = _filmstrip.value.filter { it.source is CaptureAssetSource.LiveViewPlaceholder }
                if (orphaned.isNotEmpty()) {
                    _filmstrip.value = _filmstrip.value.filterNot { it in orphaned }
                    orphaned.map(FilmstripItem::thumbnail).distinct().forEach(::scheduleBitmapRecycle)
                }
            }
            appendFilmstrip(item)
            if (!repository.isStreaming.value) scheduleLutPreviewStrip(capture.jpeg, force = true)
            if (kind == CaptureAssetKind.Photo) {
                queueBakedLutDerivative(item, capture.jpeg, capture.originalSizeRequested)
            }
            pendingThumbnail = null
            item
        } finally {
            pendingThumbnail?.let { thumbnail ->
                if (!thumbnail.isRecycled) thumbnail.recycle()
            }
        }
    }

    private fun appendPendingPhotoCapture(
        remoteUri: java.net.URI,
        isOriginal: Boolean,
        placeholder: Bitmap,
        title: String,
    ): FilmstripItem {
        val itemId = UUID.randomUUID().toString()
        return FilmstripItem(
            id = itemId,
            kind = CaptureAssetKind.Photo,
            title = title,
            source = CaptureAssetSource.LiveViewPlaceholder,
            thumbnail = placeholder,
            importedCapture = ImportedCapture(
                id = itemId,
                postviewRemoteUrl = remoteUri,
                postviewIsOriginal = isOriginal,
                isLiveViewPlaceholder = true,
            ),
        ).also(::appendFilmstrip)
    }

    private suspend fun appendPendingPhysicalCapture(pending: PendingRemoteCapture) {
        if (_filmstrip.value.any {
                it.importedCapture?.postviewRemoteUrl == pending.remoteCapture.postviewUri
            }
        ) return
        val placeholder = withContext(Dispatchers.Default) {
            pending.liveViewJpeg?.let { jpeg ->
                runCatching { imageProcessor.thumbnail(jpeg) }.getOrNull()
            } ?: Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.DKGRAY)
            }
        }
        appendPendingPhotoCapture(
            remoteUri = pending.remoteCapture.postviewUri,
            isOriginal = pending.remoteCapture.postviewUri.toString().contains("Original", ignoreCase = true) ||
                pending.remoteCapture.postviewUri.toString().contains("Origin", ignoreCase = true),
            placeholder = placeholder,
            title = "Downloading",
        )
        _message.value = UiMessage("Camera photo captured; downloading now")
    }

    private fun updateDownloadProgress(progress: CaptureDownloadProgress) {
        _filmstrip.value = _filmstrip.value.map { item ->
            val capture = item.importedCapture
            if (capture?.postviewRemoteUrl != progress.remoteUri) item else item.copy(
                importedCapture = capture.copy(
                    downloadBytes = progress.bytesRead,
                    downloadTotalBytes = progress.totalBytes,
                    downloadAttempt = progress.attempt,
                    downloadFailed = progress.failed,
                ),
            )
        }
    }

    private suspend fun replacePendingPhysicalCapture(capture: SavedCapture): Boolean {
        val remoteUri = capture.postviewRemoteUri ?: return false
        val pendingItems = _filmstrip.value.filter {
            it.importedCapture?.postviewRemoteUrl == remoteUri &&
                it.source is CaptureAssetSource.LiveViewPlaceholder
        }
        if (pendingItems.isEmpty()) return false
        val thumbnail = withContext(Dispatchers.Default) { imageProcessor.thumbnail(capture.jpeg) }
        val dimensions = withContext(Dispatchers.Default) {
            BitmapFactory.Options().also { options ->
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(capture.jpeg, 0, capture.jpeg.size, options)
            }.let { it.outWidth to it.outHeight }
        }
        _filmstrip.value = _filmstrip.value.map { current ->
            if (current !in pendingItems) current else current.copy(
                title = "Camera",
                source = CaptureAssetSource.MediaStore(capture.uri),
                thumbnail = thumbnail,
                importedCapture = requireNotNull(current.importedCapture).copy(
                    previewUri = capture.uri.takeUnless { capture.originalSizeRequested },
                    originalUri = capture.uri.takeIf { capture.originalSizeRequested },
                    originalImportState = OriginalImportState.Imported,
                    isLiveViewPlaceholder = false,
                    width = dimensions.first,
                    height = dimensions.second,
                    downloadBytes = capture.bytes.toLong(),
                    downloadTotalBytes = capture.bytes.toLong(),
                ),
            )
        }
        pendingItems.map(FilmstripItem::thumbnail).distinct().forEach(::scheduleBitmapRecycle)
        if (!repository.isStreaming.value) scheduleLutPreviewStrip(capture.jpeg, force = true)
        return true
    }

    private fun appendFilmstrip(item: FilmstripItem) {
        val updated = _filmstrip.value + item
        _filmstrip.value = updated
    }

    private suspend fun processOriginalImport(captureId: String) {
        try {
            waitForOriginalImportWindow(captureId)
            val item = _filmstrip.value.firstOrNull { it.id == captureId } ?: return
            val capture = item.importedCapture ?: return
            if (capture.originalImportState != OriginalImportState.Queued) return
            val remoteUri = capture.postviewRemoteUrl?.takeIf { capture.postviewIsOriginal }
            val contentReference = capture.cameraContentId
            if (remoteUri == null && contentReference == null) {
                updateImportedCapture(captureId, ::markOriginalImportFailed)
                _message.value = UiMessage(
                    "Original import requires a retained Original URL or Contents Transfer reference",
                    true,
                )
                return
            }
            updateImportedCapture(captureId, ::markOriginalImportDownloading)
            val imported = if (remoteUri != null) {
                repository.importOriginalPostview(remoteUri)
            } else {
                pauseLiveViewForSession()
                try {
                    repository.importOriginalFromContentsTransfer(requireNotNull(contentReference))
                } finally {
                    if (!isDisconnecting) {
                        withTimeoutOrNull(LIVEVIEW_RESUME_TIMEOUT_MILLIS) {
                            resumeLiveViewAfterSession()
                        }
                    }
                }
            }
            val dimensions = withContext(Dispatchers.Default) {
                BitmapFactory.Options().also { options ->
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(imported.jpeg, 0, imported.jpeg.size, options)
                }.let { it.outWidth to it.outHeight }
            }
            check(dimensions.first > 0 && dimensions.second > 0) {
                "Imported Original has invalid JPEG dimensions"
            }
            val thumbnail = withContext(Dispatchers.Default) {
                imageProcessor.thumbnail(imported.jpeg)
            }
            var oldThumbnail: Bitmap? = null
            _filmstrip.value = _filmstrip.value.map { current ->
                if (current.id != captureId) return@map current
                oldThumbnail = current.thumbnail
                current.copy(
                    source = CaptureAssetSource.MediaStore(imported.uri),
                    thumbnail = thumbnail,
                    importedCapture = requireNotNull(current.importedCapture).copy(
                        originalUri = imported.uri,
                        originalImportState = OriginalImportState.Imported,
                        width = dimensions.first,
                        height = dimensions.second,
                    ),
                )
            }
            oldThumbnail?.takeIf { it !== thumbnail }?.let(::scheduleBitmapRecycle)
            _filmstrip.value.firstOrNull { it.id == captureId }?.let { updated ->
                queueBakedLutDerivative(updated, imported.jpeg, originalResolution = true)
            }
            _message.value = UiMessage("Original imported to Pictures/Sony Remote")
        } catch (error: Throwable) {
            if (error is CancellationException) {
                val userCancelled = userCancelledOriginalImports.remove(captureId)
                updateImportedCapture(captureId) { capture ->
                    when {
                        userCancelled -> cancelOriginalImport(capture)
                        isDisconnecting -> markOriginalImportFailed(capture)
                        else -> capture.copy(originalImportState = OriginalImportState.Queued)
                    }
                }
                if (!userCancelled && !isDisconnecting) originalImportRequests.trySend(captureId)
                throw error
            }
            updateImportedCapture(captureId, ::markOriginalImportFailed)
            _message.value = UiMessage(
                error.readableMessage("Original import failed; the preview remains available"),
                true,
            )
        }
    }

    private suspend fun waitForOriginalImportWindow(captureId: String) {
        while (true) {
            if (_filmstrip.value.none {
                    it.id == captureId &&
                        it.importedCapture?.originalImportState == OriginalImportState.Queued
                }
            ) return
            check(connection.value is ConnectionState.Ready) { "Camera connection is unavailable" }
            val cameraWorkActive = _isBusy.value ||
                _isContinuousBurstActive.value ||
                repository.isPhysicalShutterTransferActive.value ||
                _captureSession.value.isActive
            if (isForeground && !cameraWorkActive) return
            delay(ORIGINAL_IMPORT_PAUSE_POLL_MILLIS)
        }
    }

    private fun updateImportedCapture(
        captureId: String,
        update: (ImportedCapture) -> ImportedCapture,
    ) {
        _filmstrip.value = _filmstrip.value.map { item ->
            if (item.id == captureId && item.importedCapture != null) {
                item.copy(importedCapture = update(item.importedCapture))
            } else {
                item
            }
        }
    }

    private fun queueBakedLutDerivative(
        sourceItem: FilmstripItem,
        sourceJpeg: ByteArray,
        originalResolution: Boolean,
    ) {
        val lut = _lutCaptureState.value
        if (!lut.bakeIntoPhotos || lut.isOriginal || sourceItem.kind != CaptureAssetKind.Photo) return
        val key = listOf(
            sourceItem.id,
            if (originalResolution) "original" else "preview",
            lut.importedLut?.takeIf { lut.importedSelected }?.label ?: lut.preset.name,
            lut.intensity.toString(),
        ).joinToString(":")
        if (key in completedLutDerivatives || lutDerivativeJobs[key]?.isActive == true) return
        val job = viewModelScope.launch {
            try {
                val processed = withContext(Dispatchers.Default) {
                    lut.importedLut?.takeIf { lut.importedSelected }?.let {
                        imageProcessor.applyLutToJpeg(sourceJpeg, it.cube, lut.intensity)
                    } ?: imageProcessor.applyLutToJpeg(sourceJpeg, lut.preset, lut.intensity)
                }
                currentCoroutineContext().ensureActive()
                val saved = repository.saveProcessedJpeg(
                    jpeg = processed,
                    prefix = "SONY_LUT",
                    sourceCount = 1,
                    metadataSourceJpeg = sourceJpeg,
                )
                appendSavedAsset(
                    capture = saved,
                    kind = CaptureAssetKind.Lut,
                    title = (lut.importedLut?.takeIf { lut.importedSelected }?.label ?: lut.preset.label)
                        .let { if (originalResolution) it else "$it Preview" },
                )
                completedLutDerivatives += key
                _message.value = UiMessage(
                    if (originalResolution) {
                        "${lut.importedLut?.label ?: lut.preset.label} Original-resolution copy saved"
                    } else {
                        "${lut.importedLut?.label ?: lut.preset.label} preview-resolution copy saved"
                    },
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _message.value = UiMessage(
                    error.readableMessage("Could not create the LUT photo copy"),
                    true,
                )
            } finally {
                lutDerivativeJobs.remove(key)
            }
        }
        lutDerivativeJobs[key] = job
    }

    private fun scheduleLutPreviewStrip(jpeg: ByteArray, force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastLutPreviewNanos < LUT_PREVIEW_REFRESH_MILLIS * NANOS_PER_MILLI) return
        lastLutPreviewNanos = now
        lutPreviewJob?.cancel()
        val intensity = _lutCaptureState.value.intensity
        val previous = _lutPreviews.value
        _lutPreviews.value = LutPreset.entries.map { preset ->
            previous.firstOrNull { it.preset == preset }?.copy(isLoading = true, failed = false)
                ?: LutPreviewItem(preset, isLoading = true)
        }
        lutPreviewJob = viewModelScope.launch {
            val generated = withContext(Dispatchers.Default) {
                LutPreset.entries.map { preset ->
                    runCatching {
                        LutPreviewItem(
                            preset = preset,
                            thumbnail = imageProcessor.lutThumbnail(jpeg, preset, intensity),
                        )
                    }.getOrElse { LutPreviewItem(preset = preset, failed = true) }
                }
            }
            val imported = _lutCaptureState.value.importedLut
            if (imported != null) {
                val thumbnail = withContext(Dispatchers.Default) {
                    runCatching {
                        imageProcessor.lutThumbnail(
                            jpeg,
                            imported.cube,
                            _lutCaptureState.value.importedIntensity,
                        )
                    }.getOrNull()
                }
                _lutCaptureState.value = _lutCaptureState.value.copy(
                    importedLuts = _lutCaptureState.value.importedLuts.map { lut ->
                        if (lut.label == imported.label) imported.copy(thumbnail = thumbnail) else lut
                    },
                )
            }
            currentCoroutineContext().ensureActive()
            val old = _lutPreviews.value.mapNotNull(LutPreviewItem::thumbnail).toSet()
            _lutPreviews.value = generated
            val retained = generated.mapNotNull(LutPreviewItem::thumbnail).toSet()
            old.filterNot(retained::contains).forEach(::scheduleBitmapRecycle)
        }
    }

    private fun pauseOriginalImportForCameraWork() {
        val activeId = activeOriginalImportId ?: return
        userCancelledOriginalImports.remove(activeId)
        repository.cancelOriginalImportIo()
        activeOriginalImportJob?.cancel()
    }

    private fun publishCaptureSession(value: CaptureSessionUiState) {
        val previous = _captureSession.value.preview
        _captureSession.value = value
        if (previous != null && previous !== value.preview) scheduleBitmapRecycle(previous)
    }

    private fun publishLutEditor(value: LutEditorUiState?) {
        val previous = _lutEditor.value?.preview
        val previousThumbnails = _lutEditor.value?.lutThumbnails
        _lutEditor.value = value
        if (previous != null && previous !== value?.preview) scheduleBitmapRecycle(previous)
        if (previousThumbnails != null && previousThumbnails !== value?.lutThumbnails) {
            previousThumbnails.values.forEach(::scheduleBitmapRecycle)
        }
    }

    private fun scheduleBitmapRecycle(bitmap: Bitmap) {
        viewModelScope.launch {
            delay(PREVIEW_RECYCLE_DELAY_MILLIS)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun removeWorkspaceAsset(item: FilmstripItem) {
        val source = item.source as? CaptureAssetSource.Workspace ?: return
        workspace.delete(source.item)
        _filmstrip.value = _filmstrip.value.filterNot { it.id == item.id }
        scheduleBitmapRecycle(item.thumbnail)
    }

    private suspend fun readAssetBytes(source: CaptureAssetSource): ByteArray = when (source) {
        is CaptureAssetSource.Workspace -> workspace.readBytes(source.item)
        is CaptureAssetSource.MediaStore -> readMediaStoreBytes(source.uri)
        CaptureAssetSource.LiveViewPlaceholder -> throw IOException(
            "The live-view placeholder is not the captured photograph",
        )
    }

    private suspend fun readMediaStoreBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Android could not open this image" }.readBytes()
        }
    }

    private suspend fun pauseLiveViewForSession() {
        liveViewRestartJob?.cancelAndJoin()
        liveViewJob?.cancel()
        repository.cancelLiveViewIo()
        liveViewJob?.join()
        liveViewJob = null
    }

    private suspend fun resumeLiveViewAfterSession() {
        if (!isForeground || isDisconnecting || connection.value !is ConnectionState.Ready) return
        try {
            if (repository.awaitLiveViewAvailability()) startLiveView()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _message.value = UiMessage(error.readableMessage("Live view is unavailable"), true)
        }
    }

    private fun closeComputationalSessions() {
        synchronized(panoramaProcessorLock) { panoramaSession?.close() }
        panoramaSession = null
        panoramaMetadataSource = null
        sessionStopRequested = true
    }

    private fun startStatePolling() {
        if (!isForeground || isDisconnecting) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch { repository.pollCameraState() }
    }

    private fun Throwable.readableMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback

    override fun onCleared() {
        isDisconnecting = true
        sessionStopRequested = true
        commandJob?.cancel()
        sessionJob?.cancel()
        lutJob?.cancel()
        lutDerivativeJobs.values.forEach(Job::cancel)
        lutDerivativeJobs.clear()
        physicalCaptureJob?.cancel()
        continuousBurstJob?.cancel()
        zoomTargetJob?.cancel()
        activeOriginalImportJob?.cancel()
        pollJob?.cancel()
        liveViewRestartJob?.cancel()
        liveViewJob?.cancel()
        lifecycleJob?.cancel()
        closeComputationalSessions()
        repository.cancelActiveIo()
        repository.close()
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_LIVE_ND_FRAMES = 4
        const val ZOOM_TARGET_TOLERANCE = 1
        const val ZOOM_TARGET_TIMEOUT_MILLIS = 20_000L
        const val ZOOM_TARGET_MAX_STEPS = 100
        const val ZOOM_STALLED_RETRY_LIMIT = 4
        const val ZOOM_STALLED_RETRY_DELAY_MILLIS = 250L
        const val MIN_COMPUTATIONAL_FRAMES = 2
        const val MAX_COMPOSITE_FRAMES = 32
        const val MAX_ALIGNMENT_ATTEMPT_MULTIPLIER = 3
        const val MAX_FILMSTRIP_ITEMS = 80
        const val MIN_PANORAMA_FRAMES = 4
        const val MAX_LIVE_ND_BURST_BATCHES = 16
        const val MAX_LIVE_ND_RETRY_BURSTS = 6
        const val MAX_CONSECUTIVE_ALIGNMENT_FAILURES = 3
        const val MAX_LIVE_ND_SESSION_MILLIS = 900_000L
        const val PREVIEW_FRAME_INTERVAL_MILLIS = 100L
        const val LUT_LIVE_PREVIEW_INTERVAL_MILLIS = 250L
        const val LUT_PREVIEW_REFRESH_MILLIS = 2_000L
        const val PREVIEW_RECYCLE_DELAY_MILLIS = 1_000L
        const val POSTVIEW_RESTORE_TIMEOUT_MILLIS = 4_000L
        const val LIVEVIEW_RESUME_TIMEOUT_MILLIS = 8_000L
        const val LIVEVIEW_FIRST_FRAME_TIMEOUT_MILLIS = 8_000L
        const val LIVEVIEW_RETRY_DELAY_MILLIS = 750L
        const val MAX_LIVEVIEW_START_ATTEMPTS = 3
        const val MAX_CONTINUOUS_BURST_MILLIS = 5_000L
        const val MIN_LIVE_ND_BURST_MILLIS = 1_000L
        const val MIN_INITIAL_LIVE_ND_BURST_MILLIS = 50L
        const val MAX_LIVE_ND_BURST_MILLIS = 60_000L
        const val LIVE_ND_ESTIMATED_FRAME_MILLIS = 750L
        const val LIVE_ND_BATCH_EVENT_TIMEOUT_MILLIS = 120_000L
        const val LIVE_ND_BATCH_QUIET_MILLIS = 8_000L
        const val LIVE_ND_BURST_TRANSFER_TIMEOUT_MILLIS = 600_000L
        const val LIVE_ND_START_RETRY_ATTEMPTS = 3
        const val LIVE_ND_START_RETRY_DELAY_MILLIS = 1_000L
        const val BURST_STOP_CHECK_MILLIS = 50L
        const val NANOS_PER_MILLI = 1_000_000L
        const val ORIGINAL_IMPORT_PAUSE_POLL_MILLIS = 100L
        val LIVE_ND_FRAME_OPTIONS = setOf(2, 4, 8, 16, 32)
    }
}

private fun captureKindForSavedName(name: String): CaptureAssetKind = when {
    name.startsWith("SONY_LIVE_ND_") -> CaptureAssetKind.LiveNd
    name.startsWith("SONY_COMPOSITE_") -> CaptureAssetKind.LiveComposite
    name.startsWith("SONY_PANORAMA_") -> CaptureAssetKind.Panorama
    name.startsWith("SONY_LUT_") -> CaptureAssetKind.Lut
    name.startsWith("SONY_SOURCE_") || name.startsWith("SONY_PANO_SOURCE_") -> CaptureAssetKind.SourceFrame
    else -> CaptureAssetKind.Photo
}

private fun captureTitleForSavedName(name: String): String = when (captureKindForSavedName(name)) {
    CaptureAssetKind.LiveNd -> "Live ND"
    CaptureAssetKind.LiveComposite -> "Composite"
    CaptureAssetKind.Panorama -> "Panorama"
    CaptureAssetKind.Lut -> "LUT"
    CaptureAssetKind.SourceFrame -> "Source"
    CaptureAssetKind.Photo -> "Photo"
}

private fun highestBurstSpeed(setting: com.ryu.sonyremote.model.CameraSetting?): String? =
    setting?.options?.maxByOrNull { option ->
        when (option.label.lowercase()) {
            "high", "hi", "fast" -> 3
            "mid", "medium", "normal" -> 2
            "low", "lo", "slow" -> 1
            else -> 0
        }
    }?.wireValue

internal fun shouldRoutePhysicalCaptureToPanorama(
    selectedMode: CaptureMode,
    session: CaptureSessionUiState,
    hasProcessor: Boolean,
    isBusy: Boolean,
    processorFrameCount: Int,
    maximumFrames: Int,
): Boolean =
    selectedMode == CaptureMode.Panorama &&
        session.mode == CaptureMode.Panorama &&
        session.isActive &&
        !session.isFinishing &&
        hasProcessor &&
        !isBusy &&
        processorFrameCount < maximumFrames

internal fun panoramaFrameLimitForMemory(maxHeapBytes: Long): Int {
    require(maxHeapBytes > 0)
    return (maxHeapBytes / PANORAMA_PREVIEW_FRAME_BUDGET_BYTES)
        .toInt()
        .coerceIn(4, 32)
}

private const val PANORAMA_PREVIEW_FRAME_BUDGET_BYTES = 12L * 1024 * 1024
private const val IMPORTED_LUT_DIRECTORY = "imported_luts"
