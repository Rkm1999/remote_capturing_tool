package com.ryu.scunetdenoiser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String TAG = "SCUNetDenoiser";
    private static final int OPEN_IMAGE_REQUEST = 1001;
    private static final int PREVIEW_MAXIMUM = 1600;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService tasks = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "scunet-app-worker");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final ModelStore modelStore = new ModelStore();
    private final ImageStore imageStore = new ImageStore();
    private final DenoiseProcessor processor = new DenoiseProcessor();
    private final NpuSupport.Vendor npuVendor = NpuSupport.detect();

    private TextView sessionStatus;
    private TextView emptyPreview;
    private TextView imageDetails;
    private TextView progressStatus;
    private TextView progressPercent;
    private ImageView imagePreview;
    private ProgressBar progressBar;
    private RadioGroup backendGroup;
    private RadioButton npuButton;
    private RadioButton dualButton;
    private Button chooseButton;
    private Button runButton;
    private Button cancelButton;
    private Button saveButton;

    private Uri selectedUri;
    private String selectedName = "image";
    private Bitmap displayedPreview;
    private File completedFile;
    private int completedWidth;
    private int completedHeight;
    private AcceleratorEngine gpuEngine;
    private AcceleratorEngine npuEngine;
    private AtomicBoolean currentCancellation = new AtomicBoolean();
    private boolean busy;
    private boolean destroyed;
    private volatile boolean compilingNpu;
    private long npuCompileStarted;

    private final Runnable compilationTicker = new Runnable() {
        @Override
        public void run() {
            if (!compilingNpu || destroyed) return;
            long elapsed = SystemClock.elapsedRealtime() - npuCompileStarted;
            progressStatus.setText(R.string.compiling_npu);
            progressPercent.setText(formatDuration(elapsed));
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        bindViews();
        bindActions();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void bindViews() {
        sessionStatus = findViewById(R.id.session_status);
        emptyPreview = findViewById(R.id.empty_preview);
        imageDetails = findViewById(R.id.image_details);
        progressStatus = findViewById(R.id.progress_status);
        progressPercent = findViewById(R.id.progress_percent);
        imagePreview = findViewById(R.id.image_preview);
        progressBar = findViewById(R.id.progress_bar);
        backendGroup = findViewById(R.id.backend_group);
        npuButton = findViewById(R.id.backend_npu);
        dualButton = findViewById(R.id.backend_dual);
        chooseButton = findViewById(R.id.choose_button);
        runButton = findViewById(R.id.run_button);
        cancelButton = findViewById(R.id.cancel_button);
        saveButton = findViewById(R.id.save_button);
        applyNpuAvailability();
        updateSessionLabel();
    }

    private void bindActions() {
        chooseButton.setOnClickListener(view -> openImagePicker());
        runButton.setOnClickListener(view -> startDenoising());
        cancelButton.setOnClickListener(view -> requestCancellation());
        saveButton.setOnClickListener(view -> saveCompletedImage());
        backendGroup.setOnCheckedChangeListener((group, checkedId) -> updateSessionLabel());
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_IMAGE_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some providers grant access only for the current process.
            }
        }
        selectImage(uri);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())
            && intent.getType() != null
            && intent.getType().startsWith("image/")) {
            if (Build.VERSION.SDK_INT >= 33) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                @SuppressWarnings("deprecation")
                Uri legacy = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                uri = legacy;
            }
        }
        if (uri != null) selectImage(uri);
    }

    private void selectImage(Uri uri) {
        if (busy) return;
        selectedUri = uri;
        clearCompletedImage();
        setBusy(true);
        currentCancellation = new AtomicBoolean();
        setIndeterminate("Loading image preview");
        tasks.execute(() -> {
            try {
                ImageStore.Preview preview = imageStore.decodePreview(
                    getContentResolver(), uri, PREVIEW_MAXIMUM);
                post(() -> {
                    if (!uri.equals(selectedUri)) {
                        preview.bitmap.recycle();
                        return;
                    }
                    selectedName = preview.displayName;
                    showPreview(preview.bitmap);
                    imageDetails.setText(getString(
                        R.string.image_details_format,
                        selectedName,
                        preview.width,
                        preview.height));
                    emptyPreview.setVisibility(View.GONE);
                    setProgressIdle("Ready to denoise");
                });
            } catch (Throwable error) {
                postError("Could not open image", error);
            } finally {
                post(() -> setBusy(false));
            }
        });
    }

    private void startDenoising() {
        Uri sourceUri = selectedUri;
        if (busy || sourceUri == null) return;
        DenoiseProcessor.Mode mode = selectedMode();
        currentCancellation = new AtomicBoolean();
        AtomicBoolean cancellation = currentCancellation;
        clearCompletedImage();
        setBusy(true);
        setIndeterminate("Preparing model");
        long operationStarted = SystemClock.elapsedRealtime();

        tasks.execute(() -> {
            try {
                File model = modelStore.ensureInstalled(
                    this,
                    cancellation,
                    (copied, total) -> postModelCopyProgress(copied, total));
                checkCanceled(cancellation);
                ensureEngines(mode, model, cancellation);
                checkCanceled(cancellation);

                post(() -> setIndeterminate("Loading full-resolution image"));
                ImageStore.RgbImage input = imageStore.decodeFull(
                    getContentResolver(), sourceUri, cancellation);
                checkCanceled(cancellation);
                final int width = input.width;
                final int height = input.height;
                final int tiles = DenoiseProcessor.tileCount(width, height);
                post(() -> {
                    imageDetails.setText(getString(
                        R.string.image_details_format,
                        selectedName,
                        width,
                        height));
                    setDeterminate(getResources().getQuantityString(
                        R.plurals.denoising_tiles, tiles, 0, tiles), 0,
                        getString(R.string.percent_format, 0));
                });

                byte[] denoised = processor.process(
                    input.pixels,
                    width,
                    height,
                    mode,
                    gpuEngine,
                    npuEngine,
                    cancellation,
                    (complete, total, elapsed) -> postTileProgress(complete, total, elapsed));
                input = null;
                checkCanceled(cancellation);

                post(() -> setIndeterminate("Encoding full-resolution JPEG"));
                File outputDirectory = new File(getCacheDir(), "completed");
                File output = new File(outputDirectory, "scunet_completed.jpg");
                imageStore.encodeJpeg(denoised, width, height, output, 96, cancellation);
                denoised = null;
                checkCanceled(cancellation);
                Bitmap preview = imageStore.decodeFilePreview(output, PREVIEW_MAXIMUM);
                long elapsed = SystemClock.elapsedRealtime() - operationStarted;
                Log.i(TAG, String.format(
                    Locale.US,
                    "DENOISE_COMPLETE mode=%s width=%d height=%d tiles=%d elapsed_ms=%d bytes=%d",
                    mode,
                    width,
                    height,
                    tiles,
                    elapsed,
                    output.length()));

                post(() -> {
                    completedFile = output;
                    completedWidth = width;
                    completedHeight = height;
                    showPreview(preview);
                    emptyPreview.setVisibility(View.GONE);
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(1000);
                    progressStatus.setText(R.string.denoising_complete);
                    progressPercent.setText(formatDuration(elapsed));
                    sessionStatus.setText(getString(
                        R.string.accelerator_ready, modeLabel(mode)));
                });
            } catch (CancellationException error) {
                post(() -> setProgressIdle("Canceled"));
            } catch (OutOfMemoryError error) {
                postError("Not enough memory. Close other apps and retry", error);
            } catch (Throwable error) {
                postError("Denoising failed", error);
            } finally {
                stopNpuTicker();
                post(() -> setBusy(false));
            }
        });
    }

    private void ensureEngines(
        DenoiseProcessor.Mode mode,
        File model,
        AtomicBoolean cancellation
    ) throws Exception {
        boolean needsNpu = mode == DenoiseProcessor.Mode.NPU || mode == DenoiseProcessor.Mode.DUAL;
        boolean needsGpu = mode == DenoiseProcessor.Mode.GPU || mode == DenoiseProcessor.Mode.DUAL;

        // NPU is intentionally compiled first so the Qualcomm JIT peak does not include a GPU session.
        if (needsNpu && npuEngine == null) {
            if (gpuEngine != null) {
                gpuEngine.close();
                gpuEngine = null;
            }
            startNpuTicker();
            long createStarted = SystemClock.elapsedRealtime();
            AcceleratorEngine created = AcceleratorEngine.createNpu(this, model);
            stopNpuTicker();
            try {
                created.warmUp();
            } catch (Throwable error) {
                created.close();
                throw error;
            }
            npuEngine = created;
            Log.i(TAG, "NPU_JIT_READY vendor=" + npuVendor + " elapsed_ms="
                + (SystemClock.elapsedRealtime() - createStarted));
            checkCanceled(cancellation);
        }
        if (needsGpu && gpuEngine == null) {
            post(() -> setIndeterminate("Preparing GPU"));
            long createStarted = SystemClock.elapsedRealtime();
            AcceleratorEngine created = AcceleratorEngine.createGpu(this, model);
            try {
                created.warmUp();
            } catch (Throwable error) {
                created.close();
                throw error;
            }
            gpuEngine = created;
            Log.i(TAG, "GPU_READY elapsed_ms="
                + (SystemClock.elapsedRealtime() - createStarted));
            checkCanceled(cancellation);
        }
        post(this::updateSessionLabel);
    }

    private void saveCompletedImage() {
        File source = completedFile;
        if (busy || source == null || !source.isFile()) return;
        setBusy(true);
        setIndeterminate("Saving image");
        int width = completedWidth;
        int height = completedHeight;
        String outputName = ImageStore.outputName(selectedName);
        tasks.execute(() -> {
            try {
                imageStore.saveToGallery(
                    getContentResolver(), source, outputName, width, height);
                post(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(1000);
                    progressStatus.setText(R.string.saved_location);
                    progressPercent.setText(getString(R.string.percent_format, 100));
                    Toast.makeText(
                        this,
                        getString(R.string.saved_image, outputName),
                        Toast.LENGTH_LONG).show();
                });
            } catch (Throwable error) {
                postError("Could not save image", error);
            } finally {
                post(() -> setBusy(false));
            }
        });
    }

    private void requestCancellation() {
        currentCancellation.set(true);
        cancelButton.setEnabled(false);
        progressStatus.setText(compilingNpu
            ? R.string.cancel_compiler
            : R.string.cancel_tile);
        progressPercent.setText("");
    }

    private void startNpuTicker() {
        compilingNpu = true;
        npuCompileStarted = SystemClock.elapsedRealtime();
        post(() -> {
            sessionStatus.setText(R.string.npu_setup);
            progressBar.setIndeterminate(true);
            mainHandler.removeCallbacks(compilationTicker);
            mainHandler.post(compilationTicker);
        });
    }

    private void stopNpuTicker() {
        compilingNpu = false;
        post(() -> mainHandler.removeCallbacks(compilationTicker));
    }

    private void postModelCopyProgress(long copied, long total) {
        int value = total <= 0 ? 0 : (int) Math.min(1000, copied * 1000 / total);
        post(() -> setDeterminate(
            "Installing denoise model",
            value,
            getString(R.string.percent_format, value / 10)));
    }

    private void postTileProgress(int complete, int total, long elapsedMillis) {
        int value = (int) Math.min(1000, complete * 1000L / total);
        post(() -> setDeterminate(
            getResources().getQuantityString(
                R.plurals.denoising_tiles, total, complete, total),
            value,
            getString(R.string.percent_format, value / 10)));
    }

    private void setDeterminate(String status, int progress, String percent) {
        progressBar.setIndeterminate(false);
        progressBar.setProgress(progress);
        progressStatus.setText(status);
        progressPercent.setText(percent);
    }

    private void setIndeterminate(String status) {
        progressBar.setIndeterminate(true);
        progressStatus.setText(status);
        progressPercent.setText("");
    }

    private void setProgressIdle(String status) {
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressStatus.setText(status);
        progressPercent.setText("");
    }

    private void setBusy(boolean value) {
        busy = value;
        chooseButton.setEnabled(!value);
        runButton.setEnabled(!value && selectedUri != null);
        saveButton.setEnabled(!value && completedFile != null && completedFile.isFile());
        backendGroup.setEnabled(!value);
        for (int index = 0; index < backendGroup.getChildCount(); index++) {
            backendGroup.getChildAt(index).setEnabled(!value);
        }
        boolean npuAvailable = npuVendor != NpuSupport.Vendor.UNSUPPORTED;
        npuButton.setEnabled(!value && npuAvailable);
        dualButton.setEnabled(!value && npuAvailable);
        cancelButton.setVisibility(value ? View.VISIBLE : View.GONE);
        cancelButton.setEnabled(value);
        LinearLayout.LayoutParams saveLayout =
            (LinearLayout.LayoutParams) saveButton.getLayoutParams();
        saveLayout.setMarginStart(value ? dp(8) : 0);
        saveButton.setLayoutParams(saveLayout);
        if (value) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateSessionLabel() {
        DenoiseProcessor.Mode mode = selectedMode();
        if (mode == DenoiseProcessor.Mode.GPU) {
            sessionStatus.setText(gpuEngine == null ? R.string.gpu : R.string.gpu_ready);
        } else if (mode == DenoiseProcessor.Mode.NPU) {
            sessionStatus.setText(npuEngine == null ? R.string.npu_jit : R.string.npu_ready);
        } else {
            sessionStatus.setText(
                gpuEngine != null && npuEngine != null
                    ? R.string.dual_ready
                    : R.string.dual_jit);
        }
    }

    private void applyNpuAvailability() {
        boolean available = npuVendor != NpuSupport.Vendor.UNSUPPORTED;
        npuButton.setEnabled(available);
        dualButton.setEnabled(available);
        if (available) {
            String description = NpuSupport.displayName(npuVendor);
            npuButton.setContentDescription("NPU · " + description);
            dualButton.setContentDescription("GPU + NPU · " + description);
        } else {
            npuButton.setText(R.string.npu_unavailable);
            dualButton.setText(R.string.gpu_npu_unavailable);
            String description = "NPU unavailable on " + NpuSupport.deviceSummary();
            npuButton.setContentDescription(description);
            dualButton.setContentDescription(description);
            backendGroup.check(R.id.backend_gpu);
        }
    }

    private DenoiseProcessor.Mode selectedMode() {
        int checked = backendGroup.getCheckedRadioButtonId();
        if (checked == R.id.backend_npu) return DenoiseProcessor.Mode.NPU;
        if (checked == R.id.backend_dual) return DenoiseProcessor.Mode.DUAL;
        return DenoiseProcessor.Mode.GPU;
    }

    private static String modeLabel(DenoiseProcessor.Mode mode) {
        if (mode == DenoiseProcessor.Mode.NPU) return "NPU";
        if (mode == DenoiseProcessor.Mode.DUAL) return "GPU + NPU";
        return "GPU";
    }

    private void showPreview(Bitmap bitmap) {
        Bitmap previous = displayedPreview;
        displayedPreview = bitmap;
        imagePreview.setImageBitmap(bitmap);
        if (previous != null && previous != bitmap && !previous.isRecycled()) previous.recycle();
    }

    private void clearCompletedImage() {
        completedFile = null;
        completedWidth = 0;
        completedHeight = 0;
        saveButton.setEnabled(false);
    }

    private void postError(String message, Throwable error) {
        Log.e(TAG, message, error);
        String detail = rootMessage(error);
        post(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            progressStatus.setText(message);
            progressPercent.setText("");
            Toast.makeText(
                this,
                detail.isBlank() ? message : message + ": " + detail,
                Toast.LENGTH_LONG).show();
        });
    }

    private void post(Runnable runnable) {
        mainHandler.post(() -> {
            if (!destroyed) runnable.run();
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String value = current.getMessage();
        if (value == null || value.isBlank()) value = current.getClass().getSimpleName();
        return value;
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void checkCanceled(AtomicBoolean cancellation) {
        if (cancellation.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operation canceled");
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        compilingNpu = false;
        mainHandler.removeCallbacksAndMessages(null);
        currentCancellation.set(true);
        if (displayedPreview != null && !displayedPreview.isRecycled()) {
            displayedPreview.recycle();
            displayedPreview = null;
        }
        try {
            tasks.execute(() -> {
                closeEngines();
            });
        } catch (Throwable ignored) {
            closeEngines();
        }
        tasks.shutdown();
        super.onDestroy();
    }

    private void closeEngines() {
        if (gpuEngine != null) {
            gpuEngine.close();
            gpuEngine = null;
        }
        if (npuEngine != null) {
            npuEngine.close();
            npuEngine = null;
        }
    }
}
