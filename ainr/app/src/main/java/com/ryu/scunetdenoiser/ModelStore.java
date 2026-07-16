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
    interface ProgressListener {
        void onProgress(long copied, long total);
    }

    private static final String ASSET = "models/scunet_192_fp16w.tflite";
    private static final String FILE_NAME = "scunet_192_fp16w.tflite";
    private static final long EXPECTED_BYTES = 105_995_728L;
    private static final String EXPECTED_SHA256 =
        "ce4d1be1dd43218d597db3c8400bcff95ce8086baed2891e8f1b66f42aa5ef64";

    File ensureInstalled(
        Context context,
        AtomicBoolean canceled,
        ProgressListener listener
    ) throws Exception {
        File directory = new File(context.getFilesDir(), "models");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create model directory");
        }
        File destination = new File(directory, FILE_NAME);
        if (destination.isFile() && destination.length() == EXPECTED_BYTES) return destination;

        File temporary = new File(directory, FILE_NAME + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Could not replace partial model");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        try (
            InputStream input = new BufferedInputStream(
                context.getAssets().open(ASSET), 1024 * 1024);
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
                listener.onProgress(copied, EXPECTED_BYTES);
            }
        } catch (Throwable error) {
            temporary.delete();
            throw error;
        }
        String actualHash = hex(digest.digest());
        if (copied != EXPECTED_BYTES || !EXPECTED_SHA256.equals(actualHash)) {
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
