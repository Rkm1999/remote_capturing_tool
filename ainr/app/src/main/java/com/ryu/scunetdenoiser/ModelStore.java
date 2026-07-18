package com.ryu.scunetdenoiser;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

final class ModelStore {
    enum Quality {
        HIGH_PERFORMANCE,
        HIGH_QUALITY
    }

    enum Variant {
        HIGH_PERFORMANCE_GPU,
        HIGH_PERFORMANCE_NPU,
        HIGH_QUALITY
    }

    interface ProgressListener {
        void onProgress(long copied, long total);
    }

    private static final ModelSpec HIGH_QUALITY_SPEC = new ModelSpec(
        "models/scunet_192_fp16w.tflite",
        "scunet_192_fp16w.tflite",
        105_995_728L,
        "ce4d1be1dd43218d597db3c8400bcff95ce8086baed2891e8f1b66f42aa5ef64");
    private static final ModelSpec HIGH_PERFORMANCE_GPU_SPEC = new ModelSpec(
        "models/litedenoise_high_noise_v2_epoch177.tflite",
        "litedenoise_high_noise_v2_epoch177.tflite",
        7_877_484L,
        "09d85d1476bb643f6b4ca203f5664e7a2afd9a2ab0112dd9d70d05545370b21e");
    private static final ModelSpec HIGH_PERFORMANCE_NPU_SPEC = new ModelSpec(
        "models/litedenoise_high_noise_v2_epoch177.tflite",
        "litedenoise_high_noise_v2_epoch177.tflite",
        7_877_484L,
        "09d85d1476bb643f6b4ca203f5664e7a2afd9a2ab0112dd9d70d05545370b21e");

    File ensureInstalled(
        Context context,
        Variant variant,
        AtomicBoolean canceled,
        ProgressListener listener
    ) throws Exception {
        ModelSpec spec = spec(variant);
        File directory = new File(context.getFilesDir(), "models");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create model directory");
        }
        File destination = new File(directory, spec.fileName);
        if (destination.isFile() && destination.length() == spec.bytes) return destination;

        File temporary = new File(directory, spec.fileName + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Could not replace partial model");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        try (
            InputStream input = new BufferedInputStream(
                context.getAssets().open(spec.asset), 1024 * 1024);
            BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(temporary), 1024 * 1024)
        ) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                checkCanceled(canceled);
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied += count;
                listener.onProgress(copied, spec.bytes);
            }
        } catch (Throwable error) {
            temporary.delete();
            throw error;
        }
        String actualHash = hex(digest.digest());
        if (copied != spec.bytes || !spec.sha256.equals(actualHash)) {
            temporary.delete();
            throw new IOException(String.format(
                Locale.US,
                "Model verification failed (%d bytes, %s)",
                copied,
                actualHash));
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace installed model");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not install model");
        }
        return destination;
    }

    String cacheKey(Variant variant) {
        return spec(variant).sha256;
    }

    private static ModelSpec spec(Variant variant) {
        if (variant == Variant.HIGH_PERFORMANCE_GPU) {
            return HIGH_PERFORMANCE_GPU_SPEC;
        }
        if (variant == Variant.HIGH_PERFORMANCE_NPU) {
            return HIGH_PERFORMANCE_NPU_SPEC;
        }
        return HIGH_QUALITY_SPEC;
    }

    private static final class ModelSpec {
        final String asset;
        final String fileName;
        final long bytes;
        final String sha256;

        ModelSpec(String asset, String fileName, long bytes, String sha256) {
            this.asset = asset;
            this.fileName = fileName;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) builder.append(String.format(Locale.US, "%02x", item & 0xff));
        return builder.toString();
    }

    private static void checkCanceled(AtomicBoolean canceled) {
        if (canceled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Model setup canceled");
        }
    }
}
