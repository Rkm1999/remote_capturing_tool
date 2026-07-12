package com.ryu.sonyremote.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class JpegImageProcessor {
    fun thumbnail(jpeg: ByteArray, maxEdge: Int = THUMBNAIL_EDGE): Bitmap =
        decode(jpeg, maxEdge = maxEdge, mutable = false)

    fun lutPreview(jpeg: ByteArray, preset: LutPreset, intensity: Float = 1f): Bitmap =
        applyLut(decode(jpeg, maxEdge = PREVIEW_EDGE, mutable = true), preset, intensity)

    fun liveViewLutPreview(jpeg: ByteArray, preset: LutPreset, intensity: Float): Bitmap =
        applyLut(decode(jpeg, maxEdge = LIVE_PREVIEW_EDGE, mutable = true), preset, intensity)

    fun liveViewLutPreview(jpeg: ByteArray, lut: CubeLut, intensity: Float): Bitmap =
        applyLut(decode(jpeg, maxEdge = LIVE_PREVIEW_EDGE, mutable = true), lut, intensity)

    fun lutThumbnail(jpeg: ByteArray, preset: LutPreset, intensity: Float): Bitmap =
        applyLut(decode(jpeg, maxEdge = LUT_THUMBNAIL_EDGE, mutable = true), preset, intensity)

    fun lutThumbnail(jpeg: ByteArray, lut: CubeLut, intensity: Float): Bitmap =
        applyLut(decode(jpeg, maxEdge = LUT_THUMBNAIL_EDGE, mutable = true), lut, intensity)

    fun applyLutToJpeg(jpeg: ByteArray, preset: LutPreset, intensity: Float = 1f): ByteArray {
        if (preset == LutPreset.Neutral || intensity <= 0f) return jpeg.copyOf()
        val bitmap = decode(jpeg, maxEdge = null, mutable = true)
        return try {
            encode(applyLut(bitmap, preset, intensity), JPEG_QUALITY)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun applyLutToJpeg(jpeg: ByteArray, lut: CubeLut, intensity: Float = 1f): ByteArray {
        if (intensity <= 0f) return jpeg.copyOf()
        val bitmap = decode(jpeg, maxEdge = null, mutable = true)
        return try {
            encode(applyLut(bitmap, lut, intensity), JPEG_QUALITY)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun decode(jpeg: ByteArray, maxEdge: Int?, mutable: Boolean): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not decode JPEG dimensions" }
        val sampleSize = maxEdge?.let { calculateSampleSize(bounds.outWidth, bounds.outHeight, it) } ?: 1
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = mutable
        }
        var decoded = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)) {
            "Could not decode JPEG"
        }
        if (maxEdge != null && maxOf(decoded.width, decoded.height) > maxEdge) {
            val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== decoded) decoded.recycle()
            decoded = scaled
        }
        decoded = applyExifOrientation(decoded, jpeg)
        if (mutable && !decoded.isMutable) {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            decoded = copy
        }
        return decoded
    }

    fun encode(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        require(quality in 1..100)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                "Could not encode processed JPEG"
            }
            output.toByteArray()
        }
    }

    private fun applyLut(bitmap: Bitmap, preset: LutPreset, intensity: Float): Bitmap {
        if (preset == LutPreset.Neutral || intensity <= 0f) return bitmap
        return applyLut(bitmap, CubeLut.forPreset(preset), intensity)
    }

    private fun applyLut(bitmap: Bitmap, lut: CubeLut, intensity: Float): Bitmap {
        if (intensity <= 0f) return bitmap
        val rows = minOf(STRIPE_ROWS, bitmap.height)
        val pixels = IntArray(bitmap.width * rows)
        var y = 0
        while (y < bitmap.height) {
            val rowCount = minOf(rows, bitmap.height - y)
            val count = bitmap.width * rowCount
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, rowCount)
            for (index in 0 until count) pixels[index] = lut.mapArgb(pixels[index], intensity)
            bitmap.setPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, rowCount)
            y += rowCount
        }
        return bitmap
    }

    private fun applyExifOrientation(bitmap: Bitmap, jpeg: ByteArray): Bitmap {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(jpeg)) }.getOrNull() ?: return bitmap
        if (exif.rotationDegrees == 0 && !exif.isFlipped) return bitmap
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            if (exif.rotationDegrees != 0) postRotate(exif.rotationDegrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun calculateSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        require(maxEdge > 0)
        var sample = 1
        while (maxOf(width / (sample * 2), height / (sample * 2)) >= maxEdge) sample *= 2
        return sample
    }

    private companion object {
        const val THUMBNAIL_EDGE = 240
        const val PREVIEW_EDGE = 1600
        const val LIVE_PREVIEW_EDGE = 960
        const val LUT_THUMBNAIL_EDGE = 200
        const val STRIPE_ROWS = 32
        const val JPEG_QUALITY = 95
    }
}
