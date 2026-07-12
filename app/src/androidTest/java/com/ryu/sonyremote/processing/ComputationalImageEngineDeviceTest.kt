package com.ryu.sonyremote.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputationalImageEngineDeviceTest {
    private val jpeg = JpegImageProcessor()
    private val engine = ComputationalImageEngine()

    @Test
    fun liveNdAveragesNewLightWhileCompositeKeepsTheBrighterPixels() {
        val base = texturedScene(1200, 800)
        val lit = base.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
            Canvas(bitmap).drawRect(500f, 300f, 700f, 500f, Paint().apply { color = Color.WHITE })
        }
        val baseJpeg = jpeg.encode(base, 96)
        val litJpeg = jpeg.encode(lit, 96)
        val baseCenter = luminance(base.getPixel(600, 400))
        val baseCorner = luminance(base.getPixel(100, 100))

        engine.newLiveNdSession().use { session ->
            session.add(baseJpeg).recycle()
            session.add(litJpeg).recycle()
            val result = jpeg.decode(session.encodeJpeg(), maxEdge = null, mutable = false)
            try {
                val center = luminance(result.getPixel(600, 400))
                val corner = luminance(result.getPixel(100, 100))
                assertTrue("ND should average the added light", center > baseCenter + 30 && center < 245)
                assertTrue("ND should preserve unchanged areas", abs(corner - baseCorner) < 18)
            } finally {
                result.recycle()
            }
        }

        engine.newLiveCompositeSession().use { session ->
            session.add(baseJpeg).recycle()
            session.add(litJpeg).recycle()
            val result = jpeg.decode(session.encodeJpeg(), maxEdge = null, mutable = false)
            try {
                val center = luminance(result.getPixel(600, 400))
                val corner = luminance(result.getPixel(100, 100))
                assertTrue("Composite should retain the bright addition", center > 235)
                assertTrue("Composite should preserve the base away from new light", abs(corner - baseCorner) < 18)
            } finally {
                result.recycle()
            }
        }

        base.recycle()
        lit.recycle()
    }

    @Test
    fun panoramaExpandsKnownOverlappingCrops() {
        val scene = texturedScene(2600, 900)
        val left = Bitmap.createBitmap(scene, 0, 0, 1700, 900)
        val right = Bitmap.createBitmap(scene, 900, 0, 1700, 900)

        engine.newPanoramaSession().use { session ->
            session.add(jpeg.encode(left, 96)).recycle()
            session.add(jpeg.encode(right, 96)).recycle()
            val output = jpeg.decode(session.encodeJpeg(), maxEdge = null, mutable = false)
            try {
                assertTrue("Panorama should expand beyond one input frame", output.width > 2350)
                assertTrue("Panorama height should remain close to the source", output.height in 850..980)
            } finally {
                output.recycle()
            }
        }

        left.recycle()
        right.recycle()
        scene.recycle()
    }

    private fun texturedScene(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val checker = if ((x / 64 + y / 64) % 2 == 0) 24 else 0
                pixels[y * width + x] = Color.rgb(
                    (35 + x * 150 / width + checker).coerceAtMost(255),
                    (45 + y * 130 / height + checker).coerceAtMost(255),
                    (55 + (x + y) * 90 / (width + height) + checker).coerceAtMost(255),
                )
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val canvas = Canvas(bitmap)
        val random = Random(6300)
        repeat(240) { index ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(random.nextInt(40, 245), random.nextInt(40, 245), random.nextInt(40, 245))
                style = if (index % 3 == 0) Paint.Style.STROKE else Paint.Style.FILL
                strokeWidth = 5f
            }
            val x = random.nextInt(20, width - 80).toFloat()
            val y = random.nextInt(20, height - 80).toFloat()
            val size = random.nextInt(18, 72).toFloat()
            if (index % 2 == 0) {
                canvas.drawCircle(x, y, size, paint)
            } else {
                canvas.drawRect(x, y, x + size * 1.7f, y + size, paint)
            }
        }
        return bitmap
    }

    private fun luminance(color: Int): Int =
        ((Color.red(color) * 2126 + Color.green(color) * 7152 + Color.blue(color) * 722) / 10_000)
}
